(ns chillalert.daily
  (:require
   [clojure.string :as str]
   [chillalert.auth :as auth]
   [chillalert.core :as core]
   [chillalert.db :as db]
   [chillalert.page-main :as page-main]
   [chillalert.telegram :as telegram]
   [chillalert.tmdb :as tmdb]
   [mount.core :as mount])
  (:import
   [java.util TimerTask]))

(def hour
  5)

;; Refresh

(defn stale?
  "Daily when something is coming, weekly otherwise"
  [show now]
  (or
    (= 1 (:in_production show))
    (some? (:next_air_date show))
    (< (:updated_at show) (- now (* 7 24 3600 1000)))))

(defn refresh-shows! []
  (let [now   (core/now)
        shows (db/q "SELECT * FROM show WHERE id IN (SELECT DISTINCT show_id FROM user_show)")]
    (doseq [show shows
            :when (stale? show now)]
      (try
        (tmdb/import-show! (:id show))
        (catch Exception e
          (core/log "Failed to refresh show" (:id show) (:name show) (ex-message e)))))))

;; Notifications

(defn new-episodes
  "Episodes of user's shows that aired on date and were not notified yet"
  [user-id date]
  (db/q "SELECT episode.*, show.name AS show_name FROM episode
         JOIN show ON show.id = episode.show_id
         JOIN user_show ON user_show.show_id = episode.show_id
         WHERE user_show.user_id = ?
           AND episode.air_date = ?
           AND episode.id NOT IN (SELECT episode_id FROM notification WHERE user_id = ?)
         ORDER BY show.name, episode.season, episode.episode"
    user-id date user-id))

(defn notify?
  "Rules from PLAN.md. all-episodes is the whole show in (season, episode) order"
  [ep all-episodes watched]
  (let [prev              (last (take-while #(not= (:id %) (:id ep)) all-episodes))
        watched-in-season (fn [s]
                            (some #(and (= s (:season %)) (watched (:id %))) all-episodes))]
    (boolean
      (or
        ;; user has watched episode right before this one
        (and prev (watched (:id prev)))
        ;; it's first episode (s01e01)
        (and (= 1 (:season ep)) (= 1 (:episode ep)))
        ;; first episode of a season and user has watched ANY episode in previous season
        (and (= 1 (:episode ep)) (watched-in-season (dec (:season ep))))
        ;; last episode of a season and user has watched ANY episode in previous or current season
        (and (= 1 (:finale ep))
          (or (watched-in-season (dec (:season ep)))
            (watched-in-season (:season ep))))))))

(defn episodes-to-notify [user-id date]
  (for [ep    (new-episodes user-id date)
        :let  [all     (page-main/show-episodes (:show_id ep))
               watched (page-main/watched-ids user-id (:show_id ep))]
        :when (notify? ep all watched)]
    ep))

(defn format-message [episodes]
  (str "New episodes are out:\n\n"
    (str/join "\n"
      (for [ep episodes]
        (str
          "— " (:show_name ep)
          " "  (page-main/episode-code ep)
          " “" (:name ep) "”"
          (when (= 1 (:finale ep)) " — Season finale!"))))))

(defn notify-user! [user date]
  (let [episodes (episodes-to-notify (:id user) date)]
    (when (seq episodes)
      (core/log "Notifying user" (:id user) "about" (count episodes) "episodes")
      (telegram/send-message! (:tg_id user) (format-message episodes))
      (let [now (core/now)]
        (doseq [ep episodes]
          (db/exec! "INSERT OR IGNORE INTO notification (user_id, episode_id, sent_at) VALUES (?, ?, ?)" (:id user) (:id ep) now))))))

(defn notify-all! [date]
  (doseq [user (db/q "SELECT * FROM user")]
    (try
      (notify-user! user date)
      (catch Exception e
        (core/log "Failed to notify user" (:id user) (ex-message e))))))

;; Scheduling

(declare schedule!)

(defn run-daily! []
  (let [today     (core/today)
        yesterday (str (.minusDays today 1))]
    (core/log "Daily job started for" yesterday)
    (try
      (refresh-shows!)
      (notify-all! yesterday)
      (auth/cleanup-logins!)
      (db/meta-set! "last_daily_run" (str today))
      (core/log "Daily job finished")
      (finally
        (schedule!)))))

(def *task
  (atom nil))

(defn schedule! []
  (let [at (core/next-time-at hour)]
    (swap! *task
      (fn [old]
        (some-> ^TimerTask old .cancel)
        (core/schedule-at run-daily! at)))
    (core/log "Next daily job at" (str at))))

(defn catch-up-needed? []
  (let [last-run (db/meta-get "last_daily_run")]
    (or (nil? last-run)
      (< (compare last-run (str (core/today))) 0))))

(mount/defstate job
  :start
  (do
    (schedule!)
    (when (catch-up-needed?)
      (core/log "Daily job missed, catching up")
      (Thread/startVirtualThread run-daily!))
    true)
  :stop
  (swap! *task (fn [old] (some-> ^TimerTask old .cancel) nil)))

(defn before-ns-unload []
  (mount/stop #'job))

(comment
  (defn preview-unwatched!
    "Sends user a message with ALL their unwatched aired episodes, ignoring notify? rules
     and not recording anything in notification table. To check how the message looks like"
    [user-id]
    (let [user     (db/q1 "SELECT * FROM user WHERE id = ?" user-id)
          episodes (db/q "SELECT episode.*, show.name AS show_name FROM episode
                          JOIN show ON show.id = episode.show_id
                          JOIN user_show ON user_show.show_id = episode.show_id
                          WHERE user_show.user_id = ?
                            AND episode.air_date IS NOT NULL
                            AND episode.air_date <= ?
                            AND episode.id NOT IN (SELECT episode_id FROM watched WHERE user_id = ?)
                          ORDER BY show.name, episode.season, episode.episode"
                     user-id (str (core/today)) user-id)]
      (telegram/send-message! (:tg_id user) (format-message episodes))))

  (preview-unwatched! 1))
