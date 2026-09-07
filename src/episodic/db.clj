(ns episodic.db
  (:require
   [episodic.core :as core]
   [mount.core :as mount]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   [org.sqlite SQLiteConfig SQLiteConfig$JournalMode SQLiteDataSource]))

(def migrations
  ["CREATE TABLE user (
      id           INTEGER PRIMARY KEY,
      tg_id        INTEGER NOT NULL UNIQUE,
      tg_username  TEXT,
      name         TEXT,
      created_at   INTEGER NOT NULL
    )"

   "CREATE TABLE session (
      token        TEXT PRIMARY KEY,
      user_id      INTEGER NOT NULL REFERENCES user(id),
      created_at   INTEGER NOT NULL
    )"

   "CREATE TABLE login (
      nonce        TEXT PRIMARY KEY,
      user_id      INTEGER REFERENCES user(id),
      created_at   INTEGER NOT NULL
    )"

   "CREATE TABLE show (
      id             INTEGER PRIMARY KEY,
      name           TEXT NOT NULL,
      poster_path    TEXT,
      network        TEXT,
      status         TEXT,
      in_production  INTEGER NOT NULL DEFAULT 0,
      first_air_date TEXT,
      last_air_date  TEXT,
      next_air_date  TEXT,
      updated_at     INTEGER NOT NULL
    )"

   "CREATE TABLE episode (
      id           INTEGER PRIMARY KEY,
      show_id      INTEGER NOT NULL REFERENCES show(id),
      season       INTEGER NOT NULL,
      episode      INTEGER NOT NULL,
      name         TEXT,
      air_date     TEXT,
      finale       INTEGER NOT NULL DEFAULT 0,
      UNIQUE (show_id, season, episode)
    )"

   "CREATE INDEX episode_air_date ON episode(air_date)"

   "CREATE TABLE user_show (
      user_id      INTEGER NOT NULL REFERENCES user(id),
      show_id      INTEGER NOT NULL REFERENCES show(id),
      added_at     INTEGER NOT NULL,
      touched_at   INTEGER NOT NULL,
      PRIMARY KEY (user_id, show_id)
    )"

   "CREATE TABLE watched (
      user_id      INTEGER NOT NULL REFERENCES user(id),
      episode_id   INTEGER NOT NULL REFERENCES episode(id),
      watched_at   INTEGER NOT NULL,
      PRIMARY KEY (user_id, episode_id)
    )"

   "CREATE TABLE notification (
      user_id      INTEGER NOT NULL REFERENCES user(id),
      episode_id   INTEGER NOT NULL REFERENCES episode(id),
      sent_at      INTEGER NOT NULL,
      PRIMARY KEY (user_id, episode_id)
    )"

   "CREATE TABLE meta (
      key          TEXT PRIMARY KEY,
      value        TEXT
    )"

   "CREATE TABLE season (
      show_id      INTEGER NOT NULL REFERENCES show(id),
      season       INTEGER NOT NULL,
      poster_path  TEXT,
      PRIMARY KEY (show_id, season)
    )"])

(def opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn make-datasource [path]
  (let [config (doto (SQLiteConfig.)
                 (.enforceForeignKeys true)
                 (.setBusyTimeout 5000)
                 (.setJournalMode SQLiteConfig$JournalMode/WAL))]
    (doto (SQLiteDataSource. config)
      (.setUrl (str "jdbc:sqlite:" path)))))

(defn migrate! [ds]
  (let [version (-> (jdbc/execute-one! ds ["PRAGMA user_version"] opts) :user_version)]
    (when (< version (count migrations))
      (core/log "Migrating DB from version" version "to" (count migrations))
      (jdbc/with-transaction [tx ds]
        (doseq [sql (drop version migrations)]
          (jdbc/execute! tx [sql]))
        (jdbc/execute! tx [(str "PRAGMA user_version = " (count migrations))])))))

(mount/defstate ds
  :start
  (doto (make-datasource (str core/storage "/episodic.sqlite"))
    (migrate!)))

(defn q
  "Query returning vector of maps with plain lower-case keyword keys"
  [sql & args]
  (jdbc/execute! ds (into [sql] args) opts))

(defn q1
  "Query returning first row or nil"
  [sql & args]
  (jdbc/execute-one! ds (into [sql] args) opts))

(defn exec!
  "Statement, returns update count"
  [sql & args]
  (-> (jdbc/execute-one! ds (into [sql] args)) :next.jdbc/update-count))

(defn meta-get [key]
  (:value (q1 "SELECT value FROM meta WHERE key = ?" key)))

(defn meta-set! [key value]
  (exec! "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value" key value))

(defn before-ns-unload []
  (mount/stop #'ds))
