(ns episodic.web
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [episodic.core :as core]
   [hiccup2.core :as html]))

(defn timestamp-url [url]
  (let [file (io/file (str "i" url))]
    (if (.exists file)
      (str "/i" url "?t=" (quot (.lastModified file) 1000))
      (str "/i" url))))

(defn render [hiccup]
  (str (html/html {:mode :html} hiccup)))

(defn page [{:keys [title]} & content]
  (str "<!DOCTYPE html>\n"
    (render
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (if title (str title " — Episodic") "Episodic")]
        [:link {:rel "stylesheet" :href (timestamp-url "/style.css")}]
        [:link {:rel "icon" :href (timestamp-url "/favicon.png") :type "image/png" :sizes "64x64"}]
        [:link {:rel "apple-touch-icon" :href (timestamp-url "/apple-touch-icon.png") :sizes "180x180"}]
        [:script {:type "module" :src (timestamp-url "/datastar_1.0.3.js")}]]
       [:body
        [:div.page content]]])))

(defn html-response
  ([body]
   (html-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "text/html; charset=UTF-8"}
    :body    body}))

(defn js-response
  "Datastar executes text/javascript responses in the browser"
  [js]
  {:status  200
   :headers {"Content-Type" "text/javascript; charset=UTF-8"}
   :body    js})

(defn redirect
  ([loc]
   (redirect 303 loc))
  ([status loc]
   {:status  status
    :headers {"Location" loc}}))

(defn not-found [req]
  {:status  404
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    (str "Path '" (:uri req) "' not found")})

(defn error-response [status message]
  {:status  status
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    message})

(defn header
  "Search input + Add show button, shared by main and search pages"
  [query]
  [:form.header {:method "get" :action "/search"}
   [:input.search {:type "text" :name "q" :value query :placeholder "Search shows" :autocomplete "off"}]
   [:button.btn {:type "submit"} "Add show"]])

(defn footer [user]
  [:div.footer
   (when user
     (list
       [:span.muted (or (:name user) (some->> (:tg_username user) (str "@")) "Logged in")]
       " · "
       [:a.muted {:href "/logout"} "Log out"]))])

(defn path-param [req idx]
  (nth (:path-params req) idx nil))

(defn parse-id [s]
  (when (and s (re-matches #"\d+" s))
    (parse-long s)))
