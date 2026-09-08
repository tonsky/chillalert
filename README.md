# Chill Alert

Tracks TV show episodes you've watched and messages you on Telegram when a new one you're
ready for is out. Clojure, SQLite, Datastar, TMDB.

See `doc/PLAN.md` for what it does and `doc/ARCHITECTURE.md` for how.

## Running

`config.edn` in the project root (gitignored):

```clojure
{:hostname        "http://localhost:8080"
 :tg/token        "<Telegram bot token>"
 :tmdb/read-token "<TMDB API read access token>"
 ;; :forced-user  <telegram id>   ; dev only: skips login and Telegram polling
 }
```

```bash
./script/run.sh     # runs the app on :8080
./script/repl.sh    # socket REPL on :5555; then (start), (reload), (stop)
./script/nrepl.sh   # same, but nREPL on :5556
```

Data is stored in `data/` (SQLite database and cached posters), created on first start.

## License

Copyright © 2026 Nikita Prokopov

Licensed under [MIT](LICENSE).
