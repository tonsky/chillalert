(ns episodic.tmdb
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [episodic.core :as core]
   [episodic.db :as db]
   [org.httpkit.client :as http])
  (:import
   [java.io File]))

(def token
  (:tmdb/read-token core/config))

(def lock
  (Object.))

(defn- request [url opts]
  ;; a few requests per second is plenty; TMDB allows ~50
  (locking lock
    (Thread/sleep 100))
  (let [{:keys [status body error]} @(http/request
                                       (merge
                                         {:method  :get
                                          :url     url
                                          :headers {"Authorization" (str "Bearer " token)
                                                    "Accept" "application/json"}
                                          :timeout 30000
                                          :as      :text}
                                         opts))]
    (when error
      (throw (ex-info (str "TMDB request failed: " url) {:url url} error)))
    (when-not (<= 200 status 299)
      (throw (ex-info (str "TMDB request failed: " url " " status) {:url url :status status :body body})))
    body))

(defn get-json [path & [query]]
  (-> (request (str "https://api.themoviedb.org/3" path) {:query-params query})
    (json/parse-string true)))

(defn poster-url
  "Full TMDB CDN url, used directly on search results"
  [poster-path]
  (when poster-path
    (str "https://image.tmdb.org/t/p/w342" poster-path)))

;; Search

(defn search [query]
  (let [{:keys [results]} (get-json "/search/tv" {:query query})]
    (for [r results
          :let [year (some-> (:first_air_date r) core/parse-date .getYear)]]
      {:id          (:id r)
       :name        (:name r)
       :year        year
       :overview    (:overview r)
       :poster-url  (poster-url (:poster_path r))})))

;; Import

(defn fetch-show
  "Show details with :seasons each containing :episodes. Specials (season 0) excluded."
  [id]
  (let [show    (get-json (str "/tv/" id))
        numbers (->> (:seasons show)
                  (map :season_number)
                  (remove #(= 0 %))
                  (sort))
        seasons (->> (partition-all 20 numbers)
                  (mapcat
                    (fn [chunk]
                      (let [append (str/join "," (map #(str "season/" %) chunk))
                            resp   (get-json (str "/tv/" id) {:append_to_response append})]
                        (for [n chunk]
                          (get resp (keyword (str "season/" n)))))))
                  (remove nil?))]
    (assoc show :seasons seasons)))

(defn- placeholder-name? [name]
  (or
    (str/blank? name)
    (some? (re-matches #"(?i)\s*(tbd|tba|episode\s*\d+)\s*" name))))

(defn- placeholder-season?
  "Announced seasons with nothing real in them yet: no air dates, no real names"
  [season]
  (let [episodes (:episodes season)]
    (or
      (empty? episodes)
      (every? #(and (str/blank? (:air_date %)) (placeholder-name? (:name %))) episodes))))

(defn poster-name
  "Show poster: 123.jpg. Season poster: 123-s2.jpg"
  [show-id season]
  (str show-id (when season (str "-s" season)) ".jpg"))

(defn- download-poster! [name poster-path]
  (let [file (io/file core/storage "posters" name)
        {:keys [status body error]} @(http/request {:method :get
                                                    :url    (poster-url poster-path)
                                                    :timeout 30000
                                                    :as     :byte-array})]
    (if (and (nil? error) (= 200 status))
      (with-open [out (io/output-stream file)]
        (.write out ^bytes body))
      (core/log "Failed to download poster" name poster-path status error))))

(defn poster-file ^File [name]
  (let [file (io/file core/storage "posters" name)]
    (when (.exists file)
      file)))

(defn- ensure-poster!
  "Downloads when the path changed since last import or the file went missing"
  [name poster-path existing-path]
  (when poster-path
    (when (or (not= poster-path existing-path)
            (nil? (poster-file name)))
      (download-poster! name poster-path))))

(defn custom-season-poster?
  "Seasons often reuse the show poster, only a distinct one is worth showing"
  [show-poster-path season-poster-path]
  (and (some? season-poster-path) (not= show-poster-path season-poster-path)))

(defn import-show!
  "Fetches show from TMDB and upserts show + episodes. Returns show id."
  [id]
  (let [show     (fetch-show id)
        existing (db/q1 "SELECT poster_path FROM show WHERE id = ?" id)
        existing-seasons (into {}
                           (map (juxt :season :poster_path))
                           (db/q "SELECT season, poster_path FROM season WHERE show_id = ?" id))
        now      (core/now)
        seasons  (remove placeholder-season? (:seasons show))]
    (core/log "Importing show" id (:name show) "with" (count seasons) "seasons")
    (db/exec!
      "INSERT INTO show (id, name, poster_path, network, status, in_production, first_air_date, last_air_date, next_air_date, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (id) DO UPDATE SET
         name = excluded.name, poster_path = excluded.poster_path, network = excluded.network,
         status = excluded.status, in_production = excluded.in_production,
         first_air_date = excluded.first_air_date, last_air_date = excluded.last_air_date,
         next_air_date = excluded.next_air_date, updated_at = excluded.updated_at"
      id
      (:name show)
      (:poster_path show)
      (-> show :networks first :name)
      (:status show)
      (if (:in_production show) 1 0)
      (not-empty (:first_air_date show))
      (not-empty (:last_air_date show))
      (-> show :next_episode_to_air :air_date not-empty)
      now)
    (doseq [season seasons]
      (db/exec!
        "INSERT INTO season (show_id, season, poster_path) VALUES (?, ?, ?)
         ON CONFLICT (show_id, season) DO UPDATE SET poster_path = excluded.poster_path"
        id (:season_number season) (:poster_path season)))
    (doseq [s (keys existing-seasons)
            :when (not (contains? (set (map :season_number seasons)) s))]
      (db/exec! "DELETE FROM season WHERE show_id = ? AND season = ?" id s))
    (doseq [season seasons
            :let [last-ep (->> (:episodes season) (map :episode_number) (reduce max 0))]
            ep     (:episodes season)]
      (db/exec!
        "INSERT INTO episode (id, show_id, season, episode, name, air_date, finale)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (id) DO UPDATE SET
           season = excluded.season, episode = excluded.episode, name = excluded.name,
           air_date = excluded.air_date, finale = excluded.finale"
        (:id ep)
        id
        (:season_number ep)
        (:episode_number ep)
        (not-empty (:name ep))
        (not-empty (:air_date ep))
        (if (= last-ep (:episode_number ep)) 1 0)))
    ;; episodes TMDB no longer lists, unless user data refers to them
    (let [ids (set (for [season seasons, ep (:episodes season)] (:id ep)))]
      (doseq [ep (db/q "SELECT id FROM episode WHERE show_id = ?" id)
              :when (not (contains? ids (:id ep)))]
        (db/exec!
          "DELETE FROM episode WHERE id = ?
             AND NOT EXISTS (SELECT 1 FROM watched WHERE episode_id = ?)
             AND NOT EXISTS (SELECT 1 FROM notification WHERE episode_id = ?)"
          (:id ep) (:id ep) (:id ep))))
    (ensure-poster! (poster-name id nil) (:poster_path show) (:poster_path existing))
    (doseq [season seasons
            :let [n (:season_number season)]
            :when (custom-season-poster? (:poster_path show) (:poster_path season))]
      (ensure-poster! (poster-name id n) (:poster_path season) (get existing-seasons n)))
    id))
