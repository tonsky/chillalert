(ns episodic.page-main
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [episodic.core :as core]
   [episodic.db :as db]
   [episodic.web :as web]))

;; Data

(defn user-shows [user-id]
  (db/q "SELECT show.* FROM show JOIN user_show ON user_show.show_id = show.id
         WHERE user_show.user_id = ? ORDER BY user_show.touched_at DESC, show.name" user-id))

(defn user-show [user-id show-id]
  (db/q1 "SELECT show.* FROM show JOIN user_show ON user_show.show_id = show.id
          WHERE user_show.user_id = ? AND show.id = ?" user-id show-id))

(defn show-episodes [show-id]
  (db/q "SELECT * FROM episode WHERE show_id = ? ORDER BY season, episode" show-id))

(defn watched-ids [user-id show-id]
  (into #{}
    (map :episode_id)
    (db/q "SELECT watched.episode_id FROM watched JOIN episode ON episode.id = watched.episode_id
           WHERE watched.user_id = ? AND episode.show_id = ?" user-id show-id)))

(defn episode-state [ep watched today]
  (cond
    (watched (:id ep))
    :watched

    (and (:air_date ep) (<= (compare (:air_date ep) today) 0))
    :available

    :else
    :upcoming))

;; Labels

(defn status-label [{:keys [status next_air_date]}]
  (case status
    "Returning Series"  (if next_air_date "Airing" "Returning")
    "Ended"             "Ended"
    "Canceled"          "Canceled"
    ("In Production" "Planned") "Upcoming"
    "Pilot"             "Pilot"
    status))

(defn years-label [{:keys [first_air_date last_air_date status]}]
  (when-some [from (some-> first_air_date core/parse-date .getYear)]
    (let [running? (contains? #{"Returning Series" "In Production" "Planned"} status)
          to       (some-> last_air_date core/parse-date .getYear)]
      (cond
        running?   (str from "–")
        (= from to) (str from)
        (nil? to)  (str from)
        :else      (str from "–" to)))))

(defn subtitle [show]
  (->> [(:network show)
        (years-label show)
        (when (:status show) (status-label show))]
    (remove str/blank?)
    (str/join " • ")))

(defn episode-code [ep]
  (format "s%02de%02d" (:season ep) (:episode ep)))

(defn hover-signals
  "[code-signal name-signal], leading underscore: not sent along with requests"
  [show]
  [(str "$_hoverCode" (:id show)) (str "$_hoverName" (:id show))])

(defn upcoming-label
  "Sep 10–Oct 2 / Oct 2 / Sep 10–TBA / TBA, nil when nothing is upcoming"
  [upcoming]
  (when (seq upcoming)
    (let [fmt   #(if-some [d (:air_date %)] (core/format-date (core/parse-date d)) "TBA")
          from  (fmt (first upcoming))
          to    (fmt (last upcoming))]
      (if (or (= 1 (count upcoming)) (= from to))
        from
        (str from "–" to)))))

;; Rendering

(defn render-season [show episodes watched today]
  (let [states   (map #(episode-state % watched today) episodes)
        upcoming (->> (map vector episodes states)
                   (filter #(= :upcoming (second %)))
                   (map first))
        [code-signal name-signal] (hover-signals show)
        hover    (fn [ep]
                   ;; while hovering, show title becomes episode title. Skipped on touch devices:
                   ;; the synthesized mouseenter would swap the title before the click lands
                   {"data-on:mouseenter" (str "matchMedia('(hover: hover)').matches && ("
                                           code-signal " = " (json/generate-string (episode-code ep)) ", "
                                           name-signal " = " (json/generate-string (or (:name ep) "")) ")")
                    "data-on:mouseleave" (str code-signal " = '', " name-signal " = ''")})
        n        (count episodes)
        ;; sprite has a distinct shape for the first, middle and last episode of a season
        position (fn [i] (cond (zero? i) "first" (= i (dec n)) "last" :else "middle"))]
    [:div.season
     (for [[i ep state] (map vector (range) episodes states)]
       (if (= :upcoming state)
         [:span.ep (merge (hover ep) {:class (str "upcoming " (position i))})]
         [:button.ep (merge (hover ep)
                       {:class         (str (name state) " " (position i))
                        :type          "button"
                        ;; shift+click marks a range, see handle-toggle
                        "data-on:click" (str "@post('/episodes/" (:id ep) "/toggle?shift=' + (evt.shiftKey ? 1 : 0))")})]))
     (when-some [label (upcoming-label upcoming)]
       [:span.upcoming-label label])]))

(defn render-show [show episodes watched]
  (let [today   (str (core/today))
        seasons (partition-by :season episodes)
        [code-signal name-signal] (hover-signals show)]
    [:div.show {:id (str "show-" (:id show))
                "data-signals" (str "{" (subs code-signal 1) ": '', " (subs name-signal 1) ": ''}")}
     [:div.poster
      (if (:poster_path show)
        [:img {:src (str "/posters/" (:id show) ".jpg?t=" (:updated_at show)) :alt ""}]
        [:div.poster-empty])]
     [:div.details
      [:h2.title
       [:span.ep-code {"data-text" code-signal}]
       [:span {"data-text" (str code-signal " ? " name-signal " : " (json/generate-string (:name show)))}
        (:name show)]]
      [:div.subtitle (subtitle show)]
      (for [season seasons]
        (render-season show season watched today))]]))

(defn render-show-for [user-id show]
  (render-show show (show-episodes (:id show)) (watched-ids user-id (:id show))))

(defn index-page [user]
  (let [shows (user-shows (:id user))]
    (web/page {}
      (web/header nil)
      (if (empty? shows)
        [:p.empty "No shows yet. Search for one above."]
        (for [show shows]
          (render-show-for (:id user) show)))
      (web/footer user))))

;; Handlers

(defn handle-index [req]
  (web/html-response (index-page (:user req))))

(defn handle-toggle
  "Plain click toggles one episode. With ?shift=1: an unwatched episode marks itself and every
   aired episode before it as watched, a watched one unwatches itself and everything after it."
  [req]
  (let [user-id    (-> req :user :id)
        episode-id (web/parse-id (web/path-param req 0))
        shift?     (= "1" (get-in req [:query-params "shift"]))
        episode    (some->> episode-id (db/q1 "SELECT * FROM episode WHERE id = ?"))
        show       (some->> episode :show_id (user-show user-id))]
    (if-not show
      (web/error-response 404 "Episode not found")
      (let [now      (core/now)
            today    (str (core/today))
            watched? (some? (db/q1 "SELECT 1 FROM watched WHERE user_id = ? AND episode_id = ?" user-id episode-id))
            {:keys [season episode]} episode]
        (cond
          (and shift? (not watched?))
          (db/exec! "INSERT OR IGNORE INTO watched (user_id, episode_id, watched_at)
                     SELECT ?, id, ? FROM episode
                     WHERE show_id = ? AND (season, episode) <= (?, ?) AND air_date <= ?"
            user-id now (:id show) season episode today)

          (and shift? watched?)
          (db/exec! "DELETE FROM watched WHERE user_id = ? AND episode_id IN
                       (SELECT id FROM episode WHERE show_id = ? AND (season, episode) >= (?, ?))"
            user-id (:id show) season episode)

          watched?
          (db/exec! "DELETE FROM watched WHERE user_id = ? AND episode_id = ?" user-id episode-id)

          :else
          (db/exec! "INSERT INTO watched (user_id, episode_id, watched_at) VALUES (?, ?, ?)" user-id episode-id now))
        (db/exec! "UPDATE user_show SET touched_at = ? WHERE user_id = ? AND show_id = ?" now user-id (:id show))
        (web/html-response (web/render (render-show-for user-id show)))))))
