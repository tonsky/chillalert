(ns episodic.auth
  (:require
   [clojure.string :as str]
   [episodic.core :as core]
   [episodic.db :as db]
   [episodic.telegram :as telegram]
   [episodic.web :as web]
   [mount.core :as mount]))

(def cookie-name
  "episodic_session")

(def cookie-attrs
  {:path      "/"
   :http-only true
   :secure    (not core/dev?)
   :max-age   (* 10 365 24 3600)
   :same-site :lax})

(def login-ttl-ms
  (* 15 60 1000))

(def forced-user
  "Telegram id, dev only: skips login entirely"
  (:forced-user core/config))

;; Users

(defn upsert-user!
  "tg-user is Telegram's User object"
  [{:keys [id username first_name last_name]}]
  (db/exec!
    "INSERT INTO user (tg_id, tg_username, name, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (tg_id) DO UPDATE SET tg_username = excluded.tg_username, name = excluded.name"
    id
    username
    (not-empty (str/join " " (remove str/blank? [first_name last_name])))
    (core/now))
  (db/q1 "SELECT * FROM user WHERE tg_id = ?" id))

(defn user-by-session [token]
  (db/q1 "SELECT user.* FROM session JOIN user ON user.id = session.user_id WHERE session.token = ?" token))

;; Middleware

(defn wrap-session [handler]
  (fn [req]
    (let [user (cond
                 forced-user
                 (or (db/q1 "SELECT * FROM user WHERE tg_id = ?" forced-user)
                   (upsert-user! {:id forced-user :first_name "Dev" :last_name "User"}))

                 :else
                 (some-> (get-in req [:cookies cookie-name :value]) user-by-session))]
      (handler (assoc req :user user)))))

(defn wrap-require-user [handler]
  (fn [req]
    (if (:user req)
      (handler req)
      (web/redirect "/login"))))

;; Telegram side

(defn- handle-start! [chat-id from nonce]
  (let [login (db/q1 "SELECT * FROM login WHERE nonce = ? AND user_id IS NULL AND created_at > ?" nonce (- (core/now) login-ttl-ms))]
    (if login
      (let [user (upsert-user! from)]
        (db/exec! "UPDATE login SET user_id = ? WHERE nonce = ?" (:id user) nonce)
        (core/log "Logged in user" (:id user) (:tg_username user))
        (telegram/send-message! chat-id "You're logged in. Go back to the browser, it will continue on its own."))
      (telegram/send-message! chat-id "This login link has expired. Open the site and try again."))))

(defn- handle-update! [update]
  (let [message (:message update)
        chat    (:chat message)
        text    (:text message)]
    (when (and text (= "private" (:type chat)))
      (if-some [[_ nonce] (re-matches #"/start\s+(\S+)\s*" text)]
        (handle-start! (:id chat) (:from message) nonce)
        (telegram/send-message! (:id chat) (str "Hi! To log in, open " core/hostname " and press “Log in with Telegram”."))))))

(defn- updates-loop [*running?]
  (loop [offset nil]
    (when @*running?
      (let [updates (try
                      (telegram/get-updates offset 30)
                      (catch InterruptedException e
                        (throw e))
                      (catch Exception e
                        (when @*running?
                          (core/log "getUpdates failed:" (ex-message e))
                          (Thread/sleep 5000))
                        nil))]
        (doseq [update updates]
          (try
            (handle-update! update)
            (catch InterruptedException e
              (throw e))
            (catch Exception e
              (core/log "Failed to handle update" (pr-str update))
              (.printStackTrace e))))
        (recur (if (seq updates)
                 (inc (:update_id (last updates)))
                 offset))))))

(mount/defstate telegram-updates
  :start
  (if forced-user
    (do
      (core/log "Forced user" forced-user "set, not polling Telegram")
      nil)
    (let [*running? (atom true)
          thread    (Thread/startVirtualThread
                      (fn []
                        (try
                          (updates-loop *running?)
                          (catch InterruptedException _))
                        (core/log "Stopped Telegram updates loop")))]
      (core/log "Started Telegram updates loop")
      {:running? *running?
       :thread   thread}))
  :stop
  (when-some [{:keys [running? thread]} telegram-updates]
    (reset! running? false)
    (.interrupt ^Thread thread)))

;; Browser side

(defn login-page [nonce]
  (web/page {:title "Log in" :class "center"}
    [:div.login {"data-on-interval__duration.2s" (str "@get('/login/poll/" nonce "')")}
     [:img.logo {:src (web/timestamp-url "/logo.png") :alt "Episodic" :width "180" :height "40"}]
     [:p "Track TV shows, get a Telegram message when a new episode is out."]
     [:a.btn {:href (str "https://t.me/" @telegram/bot-username "?start=" nonce)
              :target "_blank"}
      "Log in with Telegram"]
     [:p.muted "Press Start in Telegram, this page will continue on its own."]]))

(defn handle-login [req]
  (if (:user req)
    (web/redirect "/")
    (let [nonce (core/random-token 16)]
      (db/exec! "INSERT INTO login (nonce, created_at) VALUES (?, ?)" nonce (core/now))
      (web/html-response (login-page nonce)))))

(defn handle-poll [req]
  (let [nonce (web/path-param req 0)
        login (db/q1 "SELECT * FROM login WHERE nonce = ?" nonce)]
    (cond
      (nil? login)
      (web/js-response "location.href = '/login'")

      (nil? (:user_id login))
      {:status 204}

      :else
      (let [token (core/random-token 32)]
        (db/exec! "INSERT INTO session (token, user_id, created_at) VALUES (?, ?, ?)" token (:user_id login) (core/now))
        (db/exec! "DELETE FROM login WHERE nonce = ?" nonce)
        (assoc (web/js-response "location.href = '/'")
          :cookies {cookie-name (assoc cookie-attrs :value token)})))))

(defn handle-logout [req]
  (when-some [token (get-in req [:cookies cookie-name :value])]
    (db/exec! "DELETE FROM session WHERE token = ?" token))
  (assoc (web/redirect "/login")
    :cookies {cookie-name (assoc cookie-attrs :value "" :max-age 0)}))

(defn cleanup-logins! []
  (db/exec! "DELETE FROM login WHERE created_at < ?" (- (core/now) (* 24 3600 1000))))

(defn before-ns-unload []
  (mount/stop #'telegram-updates))
