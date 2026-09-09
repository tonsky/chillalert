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

# Ordering shows

We want to avoid explicitly marking shows as “Watching”/“Paused”/“Stopped watching”. These statuses require extra maintenance and can often be confusing. Say I “Stopped watching” a show but then watched another episode. Should I change status back? No? When should I? Or I watched the pilot and kinda let the rest slide. At what point should I mark this as “Stopped watching”?

So the only thing we want to track are facts. You watching an episode is a fact. New episode dropping is a fact. Everything else is an interpretation.

How to we combine these facts and decide which shows to bubble up to the top of the list?

The easiest one is the show I am currently watching. If I watched something recently, it should go to top. So “date when I marked episode watched” is a pretty strong signal.

This fails with the upcoming shows, of course. Second main use-case for a tracking app is to be notified when new season drops. If I watched last one before, there might be a year since I last marked an episode watched. And yet it should go to the top of the list. So I guess “date of last released episode” is a signal too.

Now comes the tricky part. We need to start thinking about different scenarios. For example: I watched the pilot, but then lost interest and decided to drop the show. But it’s a popular show and they keep making new seasons of it. Obviously, I don’t want to be notified about each of them. So there’s something in “number of episodes between last watched and last released”.

Even trickier, and this is a feature I was unable to find in other tracking apps. Binge watching. Say I like the show but want to wait until the whole season is released. So to the app it’ll look like I’m ignoring the show when in fact I’m still interested but just waiting for the entire thing to become available.

And then the upcoming shows. I don’t want them to be buried (e.g. go after everything unwatched) or be shown at the top (chronologically they are in the future, so will sort first), but I do want to be hyped by them, more so as the release date comes closer.

Finally, there are shows that I just added. They probably should go to the top of the list, right?

So this is the system I came up with:

- Collect following dates per show
  - Last date when I marked episode watched (not the date of the episode! Date when it was marked)
  - Air date for the episode immediately after last one I watched (last by episode number, not date marked. So if I mark e.g. s05e06 then s03e12 last one will be s05e06 because it's bigger number)
  - Air date for the last episode of a season I am currently watching (so if I last watched s03e05 that would be s03e10 assuming s03 has 10 episodes)
  - Air date for the first episode of a season immediately after last one I watched (so if I last watched s03e05 that would be s04e01) if available
  - Air date for the last episode of a season immediately after last one I watched (so if I last watched s03e05 that would be s04e10 assuming s04 has 10 episodes) if available
  - Date I added the show to "My shows"
- Pair these dates with priorities in order they are listed (Last date when I marked episode watched -> priority 1, Air date for the episode immediately after last one I watched -> priority 2 etc)
- For each of these dates, calculate _absolute_ distance from today, in days. So assuming today is Sep 15, then Sep 10 would be 5 and Sep 20 would also be 5.
- Sort ascending lexographically by vector: [<distance in days> <priority> <Show’s name>] (compare distance first, if equal, proceed to compare priorities, then names)
