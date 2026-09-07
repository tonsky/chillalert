# Architecture

Companion to PLAN.md.

Decisions: all times are UTC (5am job, "yesterday", "today" for availability). Season 0
(specials) is never imported. Episodes without air date are shown as upcoming and render
as `TBA` in the date range. No "remove show" for now. `config.edn` stays in the repo root,
gitignored.

## Dependencies

```clojure
org.clojure/clojure
io.github.tonsky/clojure-plus
mount/mount
http-kit/http-kit                    ;; server AND client (TMDB, Telegram) — no clj-http
ring/ring-core                       ;; params, cookies, head, mime
io.github.tonsky/clj-simple-router
hiccup/hiccup                        ;; hiccup2.core
cheshire/cheshire
com.github.seancorfield/next.jdbc
org.xerial/sqlite-jdbc
```

Datastar is vendored as a static file (`i/datastar.js`), no CDN. SSE helpers
(`datastar-patch-elements`, `datastar-patch-signals`) are ~30 lines, written by hand, no SDK.

## Layout

```
config.edn            hostname, tg token, tmdb token (gitignored)
data/                 gitignored runtime dir
  episodic.sqlite
  posters/<show-id>.jpg
i/                    static: style.css, datastar.js, favicon
src/episodic/
  core.clj            config, log, dev?, time helpers, timer (like site.core / allekinos.core)
  db.clj              datasource (mount), migrations, small query helpers
  tmdb.clj            TMDB client, show/season import, poster caching
  telegram.clj        Bot API client, getUpdates loop (mount), /start handler
  auth.clj            login page, nonce, session cookie, wrap-session / wrap-require-user
  datastar.clj        SSE response helpers
  page-main.clj       main page render + toggle handler
  page-search.clj     search + add
  daily.clj           5am job: refresh + notifications
  server.clj          routes, middleware, http-kit server
  main.clj            -main
```

## Database

SQLite via next.jdbc, raw SQL strings. One `jdbc/get-datasource`, pragmas set on start:
`journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000`.
Timestamps are epoch millis (INTEGER). Dates are ISO `YYYY-MM-DD` TEXT (sortable, comparable
with `date('now')`, which is UTC in SQLite).

```sql
-- Telegram user id doubles as chat id for private chats, so it's all we need to message them.
CREATE TABLE user (
  id           INTEGER PRIMARY KEY,
  tg_id        INTEGER NOT NULL UNIQUE,
  tg_username  TEXT,
  name         TEXT,
  created_at   INTEGER NOT NULL
);

-- opaque token lives in cookie; deleting row = logout everywhere
CREATE TABLE session (
  token        TEXT PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES user(id),
  created_at   INTEGER NOT NULL
);

-- deep-link handshake, rows are short-lived (15 min)
CREATE TABLE login (
  nonce        TEXT PRIMARY KEY,
  user_id      INTEGER REFERENCES user(id),   -- NULL until bot receives /start <nonce>
  created_at   INTEGER NOT NULL
);

-- id = TMDB id. Cached copy of TMDB, shared between users.
CREATE TABLE show (
  id             INTEGER PRIMARY KEY,
  name           TEXT NOT NULL,
  poster_path    TEXT,                 -- TMDB path; file at data/posters/<id>.jpg
  network        TEXT,                 -- first of TMDB `networks`: "HBO", "Prime Video", "Netflix"
  status         TEXT,                 -- "Returning Series" | "Ended" | "Canceled" | "In Production" | "Planned" | "Pilot"
  in_production  INTEGER NOT NULL DEFAULT 0,
  first_air_date TEXT,
  last_air_date  TEXT,                 -- TMDB `last_air_date`, drives the years label
  next_air_date  TEXT,                 -- TMDB `next_episode_to_air.air_date`, drives refresh cadence
  updated_at     INTEGER NOT NULL      -- last successful TMDB refresh
);

-- id = TMDB episode id. No season table: seasons are derived (GROUP BY season).
-- Season 0 (specials) is not imported.
CREATE TABLE episode (
  id           INTEGER PRIMARY KEY,
  show_id      INTEGER NOT NULL REFERENCES show(id),
  season       INTEGER NOT NULL,
  episode      INTEGER NOT NULL,
  name         TEXT,
  air_date     TEXT,                   -- NULL = TBA
  finale       INTEGER NOT NULL DEFAULT 0,  -- episode == season.episode_count per TMDB
  UNIQUE (show_id, season, episode)
);

CREATE TABLE user_show (
  user_id      INTEGER NOT NULL REFERENCES user(id),
  show_id      INTEGER NOT NULL REFERENCES show(id),
  added_at     INTEGER NOT NULL,
  touched_at   INTEGER NOT NULL,       -- add / toggle bumps this; main page sorts DESC
  PRIMARY KEY (user_id, show_id)
);

-- presence = watched; untoggling deletes the row. watched_at answers "when was it clicked".
CREATE TABLE watched (
  user_id      INTEGER NOT NULL REFERENCES user(id),
  episode_id   INTEGER NOT NULL REFERENCES episode(id),
  watched_at   INTEGER NOT NULL,
  PRIMARY KEY (user_id, episode_id)
);

-- idempotency for the daily job: never notify the same (user, episode) twice,
-- even if the job re-runs or catches up after downtime.
CREATE TABLE notification (
  user_id      INTEGER NOT NULL REFERENCES user(id),
  episode_id   INTEGER NOT NULL REFERENCES episode(id),
  sent_at      INTEGER NOT NULL,
  PRIMARY KEY (user_id, episode_id)
);

-- key/value: schema version, last_daily_run
CREATE TABLE meta (
  key          TEXT PRIMARY KEY,
  value        TEXT
);
```

Migrations: a vector of `[version sql...]` in `db.clj`, applied in order on start against
`PRAGMA user_version`. Same idea as grumpy.migrations, but without separate namespaces.

Episode states for rendering, computed in Clojure after one query per page:

- `watched`   — row in `watched`
- `available` — `air_date <= today` (UTC) and not watched
- `upcoming`  — `air_date > today` or `air_date IS NULL` (TBA)

Main page loads with three queries: user's shows (join user_show, order by touched_at desc),
all their episodes (`WHERE show_id IN (...)`), all their watched rows. Group in memory.

## TMDB (`tmdb.clj`)

Bearer token from config. Endpoints:

- `GET /3/search/tv?query=` — search page. Not cached.
- `GET /3/tv/{id}?append_to_response=season/1,season/2,...` — one request per show returns
  show + all seasons with episodes (TMDB allows up to 20 appended; chunk if more).
  First request without append to learn the season list, second with append.
- Posters: `https://image.tmdb.org/t/p/w342{poster_path}`, downloaded to `data/posters/<id>.jpg`
  when show is added or `poster_path` changes. Served by us at `/posters/<id>.jpg?t=<updated_at>`
  with a long cache header. Search results use the TMDB CDN URL directly (not cached).

`import-show!` upserts `show` and all episodes (`INSERT ... ON CONFLICT DO UPDATE`), sets
`finale` from `episode_count`, refreshes poster. Placeholder seasons (announced but
`episode_count` 0, or every episode has no air date and no name) are skipped so they don't
render as an empty row.

`status` is a fixed TMDB enum; `Returning Series` is sticky (True Detective still has it),
so `in_production` and `next_episode_to_air` are the useful "anything coming" signals. Used by both "add show" and the daily refresh.
Requests are throttled to a few per second by a simple lock + sleep; TMDB limit is ~50 rps
so this is only politeness.

## Telegram (`telegram.clj`)

- `post!` like grumpy: `https://api.telegram.org/bot<token>/<method>`, JSON body via http-kit client.
- Updates via long polling `getUpdates`, a mount state running a loop on a virtual thread,
  `offset` tracked in memory. No public URL needed in dev, no webhook setup in prod.
- The only command handled is `/start <nonce>`: upsert `user` by `tg_id`, set `login.user_id`,
  reply "Logged in, go back to the browser". Anything else gets a one-line help reply.
- `send-message!` for notifications. Message text is plain (no markdown parse mode) so show
  and episode names can't break formatting.

## Auth (`auth.clj`)

Flow:

1. `GET /login` — creates `login` row with random nonce (16 bytes SecureRandom, base64url),
   renders page with a button `https://t.me/<bot>?start=<nonce>` and
   `data-on-interval__duration.2s="@get('/login/poll?nonce=...')"`.
2. Bot receives `/start <nonce>`, fills in `login.user_id`.
3. `GET /login/poll` — if `login.user_id` is set: create `session`, delete `login` row,
   respond with `Set-Cookie: session=<token>` and a Datastar script `location.href='/'`.
   Otherwise empty 204.
4. `GET /logout` — deletes session row, clears cookie.

Cookie attrs like grumpy: `path=/ httponly secure(prod) samesite=lax max-age=10y`.
`wrap-session` looks up the token and attaches `:user` to the request. `wrap-require-user`
redirects to `/login`. Dev convenience: `:forced-user` in config skips all this.
Stale `login` rows are deleted by the daily job.

## Pages

Server-rendered hiccup, Datastar for interactivity. Every interactive response is an SSE
stream of `datastar-patch-elements` replacing an element by id.

### Main `GET /`

Sketch: poster left, title + one row of squares per season, upcoming date range after the row.

- One `[:div.show {:id "show-123"}]` per show. Toggling re-renders and patches just that div.
  Order is not re-sorted live; it changes on next full load.
- Subtitle under the title: `<network> • <years> • <status>`, e.g. `HBO • 2020–2022 • Ended`.
  Parts that are missing are dropped along with their separator.
  - network: `show.network`.
  - years: `first_air_date` year to `last_air_date` year. Collapsed to one year when equal.
    For `Returning Series` / `In Production`, open-ended: `2022–`. No first air date: omitted.
  - status label: `Returning Series` → `Returning`, `Ended` → `Ended`, `Canceled` → `Canceled`,
    `In Production` / `Planned` → `Upcoming`, `Pilot` → `Pilot`.
- Square: `[:button.ep {:class state, :data-on:click "@post('/episodes/<id>/toggle')"}]`.
  Upcoming squares are inert. Tooltip (`title`) shows `s01e04 · Name · date`.
- Date range label: from episodes of that season with state `upcoming`, in
  `(episode)` order. TBA episodes are always rendered as squares. Range = first upcoming to
  last upcoming, where a TBA endpoint prints as `TBA`: `Sep 10–Oct 2`, `Sep 10–TBA`, `TBA`.
  Collapsed to `Oct 2` when first = last, year appended when ≠ current year. No label when
  nothing is upcoming.
- `POST /episodes/:id/toggle` — insert into `watched` or delete, bump `user_show.touched_at`,
  respond with the patched show div.
- Header: search input + "Add show" button linking to `/search?q=...` (as in sketch).

### Search `GET /search`

Input bound to a signal, button `@get('/search/results')` patches the results list. Each result:
poster (TMDB CDN), name, year, "Add" button `@post('/shows/add?id=<tmdb-id>')` which imports
the show, inserts `user_show`, and redirects to `/` via script. Already-added shows show a
"Added" label instead. No "remove show" yet.

## Daily job (`daily.clj`)

`core/timer` (java.util.Timer, mount state, same as tonsky.me) schedules `run!` at the next
05:00 UTC and reschedules itself. On startup, if `meta.last_daily_run` is before today (UTC),
run immediately (catch-up after downtime). `run!` is idempotent thanks to the
`notification` table.

Steps:

1. Refresh: `import-show!` for every show referenced by any `user_show`. Daily when
   `in_production` is set or `next_air_date` is known, weekly otherwise (Ended, Canceled,
   and dormant Returning Series). Never stops entirely: TMDB corrects old data too.
2. `yesterday` = today (UTC) minus 1 day. New episodes = `air_date = yesterday`.
3. For each user, for each new episode E = sNeM of a show they track, notify E if not already
   in `notification` and any of (rules from PLAN.md):
   - **Caught up**: the episode preceding E in `(season, episode)` order across the whole show
     is watched;
   - **Pilot**: E is s01e01;
   - **Season premiere**: M = 1 and the user has watched any episode of season N−1;
   - **Season finale**: E is `finale` and the user has watched any episode of season N−1 or
     season N. Fires even mid-season, by design (binge-watchers).
4. Group per user, send one message:

   ```
   New episodes are out:
     - Reacher / s04e08 / Vote for Sampson / Season finale
     - Lanterns / s01e04 / The Weenie
   ```

   Sorted by show name, then season/episode. Insert `notification` rows after a successful
   send. A full-season drop (Netflix) yields the premiere and the finale in the same message,
   which reads fine.

5. Delete `login` rows older than a day.

## Server (`server.clj`)

Middleware stack bottom to top, same shape as allekinos: 404 fallback → `wrap-routes` →
`wrap-params` → `wrap-head` → `wrap-cookies` → `auth/wrap-session` → `wrap-errors`.
Static `/i/**` and `/posters/**` served with long `Cache-Control` and `?t=` cache busting.

## Dev workflow

`script/repl.sh` as now; `user/reload` via clj-reload with `before-ns-unload` stopping mount
states (server, timer, telegram loop, datasource). `dev?` = hostname is localhost.
