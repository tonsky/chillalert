(ns episodic.page-search
  (:require
   [clojure.string :as str]
   [episodic.core :as core]
   [episodic.db :as db]
   [episodic.tmdb :as tmdb]
   [episodic.web :as web]))

(defn added-show-ids [user-id]
  (into #{} (map :show_id) (db/q "SELECT show_id FROM user_show WHERE user_id = ?" user-id)))

(defn render-result [result added?]
  [:div.result
   [:div.result-poster
    (if-some [url (:poster-url result)]
      [:img {:src url :alt "" :loading "lazy"}]
      [:div.poster-empty])]
   [:div.result-details
    [:div.result-title
     (:name result)
     (when-some [year (:year result)]
       [:span.muted (str " (" year ")")])]
    (when-not (str/blank? (:overview result))
      [:div.result-overview (:overview result)])
    (if added?
      [:span.muted "Added"]
      [:form {:method "post" :action (str "/shows/" (:id result) "/add")}
       [:button.btn.btn-small {:type "submit"} "Add to my shows"]])]])

(defn search-page [user query]
  (let [results (when-not (str/blank? query)
                  (tmdb/search query))
        added   (added-show-ids (:id user))]
    (web/page {:title (if (str/blank? query) "Search" query)}
      (web/header query)
      (cond
        (str/blank? query)
        [:p.empty "Type a show name and press “Add show”."]

        (empty? results)
        [:p.empty "Nothing found for “" query "”."]

        :else
        (for [result results]
          (render-result result (contains? added (:id result)))))
      (web/footer user))))

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
