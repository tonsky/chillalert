(ns chillalert.page-search
  (:require
   [clojure.string :as str]
   [chillalert.core :as core]
   [chillalert.db :as db]
   [chillalert.page-main :as page-main]
   [chillalert.tmdb :as tmdb]
   [chillalert.web :as web]))

(defn added-show-ids [user-id]
  (into #{} (map :show_id) (db/q "SELECT show_id FROM user_show WHERE user_id = ?" user-id)))

(defn result-subtitle
  "Main page subtitle (network • years • status) plus season and episode counts"
  [result]
  (let [count-label (fn [n word] (when (and n (pos? n)) (str n " " word (when (not= 1 n) "s"))))]
    (->> [(page-main/subtitle result nil (str (core/today)))
          (count-label (:seasons result) "season")
          (count-label (:episodes result) "episode")]
      (remove str/blank?)
      (str/join " • "))))

(defn render-result
  "Same structure as page-main/render-show, with the episode grid replaced
   by the show overview and an Add button"
  [result added?]
  [:div.show
   [:div.poster
    (if-some [url (:poster-url result)]
      [:img {:src url :alt "" :loading "lazy"}]
      [:div.poster-empty])]
   [:div.details
    [:h2.title (:name result)]
    [:div.subtitle (result-subtitle result)]
    (when-not (str/blank? (:overview result))
      [:div.overview (:overview result)])
    (if added?
      [:span.add.muted "Added"]
      [:form.add {:method "post" :action (str "/shows/" (:id result) "/add")}
       [:button.btn.btn-small {:type "submit"} "Add"]])]])

(defn search-page [user query]
  (let [results (when-not (str/blank? query)
                  (tmdb/search query))
        added   (added-show-ids (:id user))]
    (web/page {:title  (if (str/blank? query) "Search" query)
               :topbar (web/topbar user :search)}
      (web/header query)
      (cond
        (str/blank? query)
        nil

        (empty? results)
        [:p.empty "Nothing found for “" query "”."]

        :else
        (for [result results]
          (render-result result (contains? added (:id result))))))))

(defn handle-search [req]
  (let [query (some-> (get-in req [:query-params "q"]) str/trim)]
    (web/html-response (search-page (:user req) query))))

(defn handle-add [req]
  (let [user-id (-> req :user :id)
        show-id (web/parse-id (web/path-param req 0))]
    (if-not show-id
      (web/error-response 400 "Bad show id")
      (let [now   (core/now)
            fresh (db/q1 "SELECT id FROM show WHERE id = ? AND updated_at > ?" show-id (- now (* 24 3600 1000)))]
        (when-not fresh
          (tmdb/import-show! show-id))
        (db/exec!
          "INSERT INTO user_show (user_id, show_id, added_at, touched_at) VALUES (?, ?, ?, ?)
           ON CONFLICT (user_id, show_id) DO UPDATE SET touched_at = excluded.touched_at"
          user-id show-id now now)
        (web/redirect "/")))))
