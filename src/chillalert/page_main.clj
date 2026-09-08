(ns chillalert.page-main
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [chillalert.core :as core]
   [chillalert.db :as db]
   [chillalert.tmdb :as tmdb]
   [chillalert.web :as web]))

;; Data

(defn user-shows [user-id]
  (db/q "SELECT show.* FROM show JOIN user_show ON user_show.show_id = show.id
         WHERE user_show.user_id = ? ORDER BY user_show.touched_at DESC, show.name" user-id))

(defn user-show [user-id show-id]
  (db/q1 "SELECT show.* FROM show JOIN user_show ON user_show.show_id = show.id
          WHERE user_show.user_id = ? AND show.id = ?" user-id show-id))

(defn custom-season-posters
  "Season numbers whose poster differs from the show's, in order"
  [show]
  (->> (db/q "SELECT season, poster_path FROM season WHERE show_id = ? ORDER BY season" (:id show))
    (filter #(tmdb/custom-season-poster? (:poster_path show) (:poster_path %)))
    (map :season)))

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

(defn airing?
  "Last season has at least one episode already out and at least one still upcoming"
  [episodes today]
  (let [season (last (partition-by :season episodes))
        aired? #(and (:air_date %) (<= (compare (:air_date %) today) 0))]
    (boolean (and (some aired? season) (some (complement aired?) season)))))

(defn status-label [{:keys [status]} airing?]
  (cond
    (and (= "Returning Series" status) airing?) "Airing"
    (= "Returning Series" status) "Returning"
    :else status))

(defn years-label
  "Returns [label ended?], ended? is false when there is no end year"
  [{:keys [first_air_date last_air_date status]}]
  (when-some [from (some-> first_air_date core/parse-date .getYear)]
    (let [running? (contains? #{"Returning Series" "In Production" "Planned"} status)
          to       (some-> last_air_date core/parse-date .getYear)]
      (cond
        running?    [(str from "–") false]
        (= from to) [(str from) true]
        (nil? to)   [(str from) false]
        :else       [(str from "–" to) true]))))

(defn subtitle [show episodes today]
  (let [[years ended?] (years-label show)
        status         (when (:status show) (status-label show (airing? episodes today)))
        ;; no end year: "2022– Airing" instead of "2022– • Airing"
        years+status   (if (and years status (not ended?))
                         (str years status)
                         (str/join " • " (remove str/blank? [years status])))]
    (->> [(:network show) years+status]
      (remove str/blank?)
      (str/join " • "))))

(defn episode-code [ep]
  (format "s%02de%02d" (:season ep) (:episode ep)))

(defn hover-signals
  "[code-signal name-signal season-signal], leading underscore: not sent along with requests"
  [show]
  [(str "$_hoverCode" (:id show)) (str "$_hoverName" (:id show)) (str "$_hoverSeason" (:id show))])

(defn drag-signals
  "[from-signal to-signal from-id-signal to-id-signal]: indexes of the anchor and the current episode
   of a mouse drag (-1 when not dragging) and their episode ids"
  [show]
  (let [id (:id show)]
    [(str "$_dragFrom" id) (str "$_dragTo" id) (str "$_dragFromId" id) (str "$_dragToId" id)]))

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

(defn render-season
  "offset: index of the season's first episode within the show, drag ranges are tracked by index"
  [show episodes watched today offset]
  (let [states   (map #(episode-state % watched today) episodes)
        upcoming (->> (map vector episodes states)
                   (filter #(= :upcoming (second %)))
                   (map first))
        [code-signal name-signal season-signal] (hover-signals show)
        [from-signal to-signal from-id-signal to-id-signal] (drag-signals show)
        ;; while hovering, show title becomes episode title and poster becomes the season's, if it has
        ;; its own. Skipped on touch devices: the synthesized mouseenter would swap the title before
        ;; the click lands
        hover-enter (fn [ep]
                      (str "matchMedia('(hover: hover)').matches && ("
                        code-signal " = " (json/generate-string (episode-code ep)) ", "
                        name-signal " = " (json/generate-string (or (:name ep) "")) ", "
                        season-signal " = " (:season ep) ")"))
        hover-leave (str code-signal " = '', " name-signal " = '', " season-signal " = 0")
        hover    (fn [ep]
                   {"data-on:mouseenter" (hover-enter ep)
                    "data-on:mouseleave" hover-leave})
        ;; press and drag across episodes of one show, in either direction and across seasons: the range
        ;; between the anchor and the current episode lights up as hovered, on release it gets the state
        ;; the anchor would have been toggled to. Release on the anchor itself is left to the click handler
        drag     (fn [ep i]
                   {"data-on:mousedown"  (str "evt.button === 0 && (evt.preventDefault(), "
                                           from-signal " = " i ", " to-signal " = " i ", "
                                           from-id-signal " = " (:id ep) ", " to-id-signal " = " (:id ep) ")")
                    "data-on:mouseenter" (str (hover-enter ep) ", "
                                           from-signal " >= 0 && (" to-signal " = " i ", " to-id-signal " = " (:id ep) ")")
                    "data-on:mouseleave" hover-leave
                    "data-class:hovered" (str from-signal " >= 0 && Math.min(" from-signal ", " to-signal ") <= " i
                                           " && " i " <= Math.max(" from-signal ", " to-signal ")")})
        n        (count episodes)
        ;; sprite has a distinct shape for the first, middle, last and only episode of a season
        position (fn [i] (cond (= n 1) "single" (zero? i) "first" (= i (dec n)) "last" :else "middle"))]
    [:div.season
     (for [[i ep state] (map vector (range) episodes states)]
       (if (= :upcoming state)
         [:span.ep (merge (hover ep) {:class (str "upcoming " (position i))})]
         [:button.ep (merge (drag ep (+ offset i))
                       {:class         (str (name state) " " (position i))
                        :type          "button"
                        ;; shift+click marks a range, see handle-toggle
                        "data-on:click" (str "@post('/episodes/" (:id ep) "/toggle?shift=' + (evt.shiftKey ? 1 : 0))")})]))
     (when-some [label (upcoming-label upcoming)]
       [:span.upcoming-label label])]))

(defn default-poster-season
  "Season whose poster to show by default: the season of the episode right after the last
   watched one, or of the last watched episode itself when everything is watched, or 1 when
   nothing is watched yet"
  [episodes watched]
  (if-some [last-watched (last (filter #(watched (:id %)) episodes))]
    (let [next-ep (second (drop-while #(not= (:id %) (:id last-watched)) episodes))]
      (:season (or next-ep last-watched)))
    1))

(defn render-show [show episodes watched season-posters]
  (let [today   (str (core/today))
        seasons (partition-by :season episodes)
        [code-signal name-signal season-signal] (hover-signals show)
        [from-signal to-signal from-id-signal to-id-signal] (drag-signals show)
        offsets (reductions + 0 (map count seasons))
        ;; base poster: the default season's, when it has a dedicated one, else the show's
        poster-season (let [n (default-poster-season episodes watched)]
                        (when (some #{n} season-posters) n))]
    [:div.show {:id (str "show-" (:id show))
                "data-signals" (str "{" (subs code-signal 1) ": '', " (subs name-signal 1) ": '', "
                                 (subs season-signal 1) ": 0, " (subs from-signal 1) ": -1, " (subs to-signal 1) ": -1, "
                                 (subs from-id-signal 1) ": 0, " (subs to-id-signal 1) ": 0}")
                ;; a drag ends wherever the mouse is released, the current episode is the range end.
                ;; Same episode as the anchor: nothing to do here, the click that follows toggles it
                "data-on:mouseup__window" (str from-signal " >= 0 && ("
                                            from-signal " !== " to-signal
                                            " && @post('/episodes/' + " from-id-signal " + '/toggle?to=' + " to-id-signal "), "
                                            from-signal " = -1, " to-signal " = -1)")}
     [:div.poster
      (if (:poster_path show)
        [:img {:src (str "/posters/" (tmdb/poster-name (:id show) nil) "?t=" (:updated_at show)) :alt ""}]
        [:div.poster-empty])
      ;; layered over the show poster while an episode of that season is hovered; the default
      ;; season's also when nothing is hovered. Seasons without a dedicated poster fall through
      ;; to the show poster underneath. Always in the DOM so they are loaded up front and the
      ;; swap is instant
      (for [n season-posters]
        [:img.season-poster {:src (str "/posters/" (tmdb/poster-name (:id show) n) "?t=" (:updated_at show))
                             :alt ""
                             "data-show" (if (= n poster-season)
                                           (str season-signal " === " n " || " season-signal " === 0")
                                           (str season-signal " === " n))}])]
     [:div.details
      [:h2.title
       [:span.ep-code {"data-text" code-signal}]
       [:span {"data-text" (str code-signal " ? " name-signal " : " (json/generate-string (:name show)))}
        (:name show)]]
      [:div.subtitle (subtitle show episodes today)]
      (for [[season offset] (map vector seasons offsets)]
        (render-season show season watched today offset))]]))

(defn render-show-for [user-id show]
  (render-show show (show-episodes (:id show)) (watched-ids user-id (:id show)) (custom-season-posters show)))

(defn index-page [user]
  (let [shows (user-shows (:id user))]
    (web/page {:topbar (web/topbar user)}
      (if (empty? shows)
        [:p.empty "No shows yet. Press “Add show” to find one."]
        (for [show shows]
          (render-show-for (:id user) show))))))

;; Handlers

(defn handle-index [req]
  (web/html-response (index-page (:user req))))

(defn handle-toggle
  "Plain click toggles one episode. With ?shift=1: an unwatched episode marks itself and every
   aired episode before it as watched, a watched one unwatches itself and everything after it.
   With ?to=<episode id> (mouse drag): everything between the two episodes of the same show,
   inclusive, gets the state the first one is toggled to."
  [req]
  (let [user-id    (-> req :user :id)
        episode-id (web/parse-id (web/path-param req 0))
        shift?     (= "1" (get-in req [:query-params "shift"]))
        to-id      (web/parse-id (get-in req [:query-params "to"]))
        episode    (some->> episode-id (db/q1 "SELECT * FROM episode WHERE id = ?"))
        to         (some->> to-id (db/q1 "SELECT * FROM episode WHERE id = ?"))
        show       (some->> episode :show_id (user-show user-id))]
    (cond
      (not show)
      (web/error-response 404 "Episode not found")

      (and to-id (not= (:show_id to) (:id show)))
      (web/error-response 404 "Episode not found")

      :else
      (let [now      (core/now)
            today    (str (core/today))
            watched? (some? (db/q1 "SELECT 1 FROM watched WHERE user_id = ? AND episode_id = ?" user-id episode-id))
            {:keys [season episode]} episode
            [[lo-season lo-episode] [hi-season hi-episode]] (when to (sort [[season episode] [(:season to) (:episode to)]]))]
        (cond
          (and to (not watched?))
          (db/exec! "INSERT OR IGNORE INTO watched (user_id, episode_id, watched_at)
                     SELECT ?, id, ? FROM episode
                     WHERE show_id = ? AND (season, episode) BETWEEN (?, ?) AND (?, ?) AND air_date <= ?"
            user-id now (:id show) lo-season lo-episode hi-season hi-episode today)

          (and to watched?)
          (db/exec! "DELETE FROM watched WHERE user_id = ? AND episode_id IN
                       (SELECT id FROM episode WHERE show_id = ? AND (season, episode) BETWEEN (?, ?) AND (?, ?))"
            user-id (:id show) lo-season lo-episode hi-season hi-episode)

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
