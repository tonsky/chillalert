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
         ORDER BY show.name, show.id, episode.season, episode.episode"
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

(defn season-drop?
  "Whole season (more than one episode) aired on date"
  [season-episodes date]
  (boolean
    (and
      (next season-episodes)
      (every? #(= date (:air_date %)) season-episodes))))

(defn notifications
  "{:episodes [ep ...], :seasons [[ep ...] ...]}. If any new episode of a show passes notify?,
   all new episodes of that show are included. Seasons released entirely on date go to :seasons"
  [user-id date]
  (let [groups (for [eps        (partition-by :show_id (new-episodes user-id date))
                     :let       [show-id (:show_id (first eps))
                                 all     (page-main/show-episodes show-id)
                                 watched (page-main/watched-map user-id show-id)]
                     :when      (some #(notify? % all watched) eps)
                     season-eps (partition-by :season eps)
                     :let       [season (:season (first season-eps))]]
                 {:drop?    (season-drop? (filter #(= season (:season %)) all) date)
                  :episodes season-eps})]
    {:episodes (mapcat :episodes (remove :drop? groups))
     :seasons  (map :episodes (filter :drop? groups))}))

(defn format-message [{:keys [episodes seasons]}]
  (str/join "\n\n"
    (concat
      (when (seq episodes)
        [(str "New episodes are out:\n\n"
           (str/join "\n"
             (for [ep episodes]
               (str
                 "— " (:show_name ep)
                 " "  (page-main/episode-code ep)
                 " “" (:name ep) "”"
                 (when (= 1 (:finale ep)) " — Season finale!")))))])
      (when (seq seasons)
        [(str (if (next seasons) "New seasons just dropped:" "New season just dropped:") "\n\n"
           (str/join "\n"
             (for [eps  seasons
                   :let [ep (first eps)]]
               (str "— " (:show_name ep) " " (format "s%02d" (:season ep)) ", " (count eps) " episodes"))))]))))

(defn notify-user! [user date]
  (let [{:keys [episodes seasons] :as notifs} (notifications (:id user) date)
        all-episodes (concat episodes (apply concat seasons))]
    (when (seq all-episodes)
      (core/log "Notifying user" (:id user) "about" (count all-episodes) "episodes")
      (telegram/send-message! (:tg_id user) (format-message notifs))
      (let [now (core/now)]
        (doseq [ep all-episodes]
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
      (telegram/send-message! (:tg_id user) (format-message {:episodes episodes}))))

  (preview-unwatched! 1))
