This is a web app for tracking TV show episodes:

- Stack: Clojure, SQLite, DataStar
- Critical backend dependencies: mount, http-kit, ring, hiccup, cheshire, clj-simple-router, next.jdbc. No HoneySQL
- Authentication via Telegram (Bot deep-link flow, https://t.me/YourBot?start=<nonce>, -> /start -> store session, have permission to message user)
- Notifications via Telegram
- Source of data themoviedb.org
- Cache show, episode info, posters

Main flow:

- User authenticates with Telegram, we create user
- User searches a shows by name
- Clicks "Add it to my shows"
- Tracks watched episodes
- Once a day (5am) we:
  - Pull in/refresh release dates for all the shows
  - For each user, check if there are new episodes released yesterday. If yes AND any of the following, add to notification list:
    - user has watched episode right before this one
    - it's first episode (s01e01)
    - it's the first episode of a season and user has watched ANY episode in previous season
    - it's the last episode of a season and user has watched ANY episode in previous or current season
  - Send Telegram message that looks like this:

```
New episodes are out:
  - Reacher / s04e08 / Vote for Sampson / Season finale
  - Lanterns / s01e04 / The Weenie
```

# Main app page

See @Sketch.png

- On a website, all shows are listed on single page.
  - Sort by last interacted with
- Next to a poster:
  - Show’s title
  - Subtitle: <network> • <years> • <status>, e.g. "HBO • 2020–2022 • Ended". Network from TMDB `networks`, years from first/last air date (open-ended "2022–" for running shows), status: Returning, Ended, Canceled, Upcoming.
  - A line per season. In a line:
    - A square icon for each episode. Three states: watched, available, upcoming.
    - User can toggle available -> watched and back (store when this was clicked, not just boolean)
    - Shift+click on unwatched episode marks it and everything before it watched. Shift+click on watched episode unwatches it and everything after it.
    - Press and drag across episodes (either direction, across seasons of the same show): the range lights up as hovered, on release it gets the state the first episode is toggled to.
    - Hovering over an episode square replaces show title with episode number + title, e.g. "s01e04 Adventures in Chicago"
    - After episodes, if there are upcoming episodes, date interval for first upcoming to last upcoming. So say show has 4 episodes out already, episode 5 is out on Sep 10 and episode 8 (season finale) on Oct 2. We will show "Sep 10..Oct 2". If one episode left, collapse to "Oct 2". In everything is out, no label. If year is different from current year, add year to.

# Search/add show page

Query text input + search button, search results underneath, "add show buttons"