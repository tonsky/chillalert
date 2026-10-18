# Chill Alert

Tracks TV show episodes you've watched and messages you on Telegram when a new one you're
ready for is out. Clojure, SQLite, Datastar, TMDB.

See `doc/PLAN.md` for what it does and `doc/ARCHITECTURE.md` for how.

## Running

Config is read from `--key value` CLI args, then env vars, then defaults:

| CLI arg             | env               | default                 |
|---------------------|-------------------|-------------------------|
| `--base-url`        | `BASE_URL`        | `http://localhost:8080` |
| `--port`            | `PORT`            | `8080`                  |
| `--data-dir`        | `DATA_DIR`        | `data`                  |
| `--db-file`         | `DB_FILE`         | `chillalert.sqlite`     |
| `--tg-token`        | `TG_TOKEN`        | Telegram bot token      |
| `--tmdb-read-token` | `TMDB_READ_TOKEN` | TMDB API read access token |
| `--forced-user`     | `FORCED_USER`     | dev only: Telegram id, skips login and Telegram polling |

`BASE_URL` starting with `http://localhost` turns on dev mode (needs the `:dev` alias). For local development, put env vars into `mise.local.toml` (gitignored):

```toml
[env]
TG_TOKEN        = "..."
TMDB_READ_TOKEN = "..."
```

```bash
./script/run.sh     # runs the app on :8080
./script/repl.sh    # socket REPL on :5555
./script/nrepl.sh   # same, but nREPL on :5556
```

`DATA_DIR` holds the SQLite database and cached posters, created on first start.

## License

Copyright © 2026 Nikita Prokopov

Licensed under [MIT](LICENSE).
