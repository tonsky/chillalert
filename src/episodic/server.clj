(ns episodic.server
  (:require
   [clj-simple-router.core :as router]
   [clojure.java.io :as io]
   [episodic.auth :as auth]
   [episodic.core :as core]
   [episodic.page-main :as page-main]
   [episodic.page-search :as page-search]
   [episodic.tmdb :as tmdb]
   [episodic.web :as web]
   [mount.core :as mount]
   [org.httpkit.server :as http]
   [ring.middleware.cookies :as ring-cookies]
   [ring.middleware.head :as ring-head]
   [ring.middleware.params :as ring-params]
   [ring.util.io :as ring-io]
   [ring.util.mime-type :as ring-mime]
   [ring.util.time :as ring-time])
  (:import
   [java.io File]))

(defn file-response [^File file]
  (if (and file (.isFile file))
    {:status  200
     :headers {"Content-Length" (str (.length file))
               "Last-Modified"  (ring-time/format-date (ring-io/last-modified-date file))
               "Content-Type"   (ring-mime/ext-mime-type (.getName file))
               "Cache-Control"  (if core/dev? "no-cache" "max-age=31536000, immutable")}
     :body    file}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body   "Not found"}))

(defn static-handler [req]
  (let [[path] (:path-params req)]
    (if (re-find #"\.\." path)
      (web/error-response 400 "Bad path")
      (file-response (io/file "i" path)))))

(defn poster-handler [req]
  (let [[name] (:path-params req)
        id     (web/parse-id (second (re-matches #"(\d+)\.jpg" (str name))))]
    (file-response (some-> id tmdb/poster-file))))

(def routes
  (router/routes
    "GET /"                      req ((auth/wrap-require-user page-main/handle-index) req)
    "POST /episodes/*/toggle"    req ((auth/wrap-require-user page-main/handle-toggle) req)
    "GET /search"                req ((auth/wrap-require-user page-search/handle-search) req)
    "POST /shows/*/add"          req ((auth/wrap-require-user page-search/handle-add) req)
    "GET /login"                 req (auth/handle-login req)
    "GET /login/poll/*"          req (auth/handle-poll req)
    "GET /logout"                req (auth/handle-logout req)
    "GET /i/**"                  req (static-handler req)
    "GET /posters/*"             req (poster-handler req)))

(defn wrap-errors [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (core/log "Request failed:" (:request-method req) (:uri req))
        (.printStackTrace e)
        (web/error-response 500 (str "Internal error: " (ex-message e)))))))

(def app
  (-> web/not-found
    (router/wrap-routes routes)
    auth/wrap-session
    ring-params/wrap-params
    ring-head/wrap-head
    ring-cookies/wrap-cookies
    wrap-errors))

(def opts
  {:legacy-return-value? false
   :ip   "0.0.0.0"
   :port (or (:port core/config) 8080)})

(mount/defstate server
  :start
  (let [server (http/run-server app opts)]
    (core/log "Started HTTP server on" (str (:ip opts) ":" (:port opts)))
    server)
  :stop
  (do
    (http/server-stop! server)
    (core/log "Stopped HTTP server")))

(defn before-ns-unload []
  (mount/stop #'server))
