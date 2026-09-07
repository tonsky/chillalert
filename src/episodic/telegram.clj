(ns episodic.telegram
  (:require
   [cheshire.core :as json]
   [episodic.core :as core]
   [org.httpkit.client :as http]))

(def token
  (:tg/token core/config))

(defn call!
  "Calls Bot API method with JSON params, returns :result"
  ([method]
   (call! method {}))
  ([method params]
   (call! method params {}))
  ([method params opts]
   (let [{:keys [status body error]} @(http/request
                                        (merge
                                          {:method  :post
                                           :url     (str "https://api.telegram.org/bot" token "/" method)
                                           :headers {"Content-Type" "application/json"}
                                           :body    (json/generate-string params)
                                           :timeout 30000
                                           :as      :text}
                                          opts))]
     (when error
       (throw (ex-info (str "Telegram " method " failed") {:method method :params params} error)))
     (let [resp (json/parse-string body true)]
       (when-not (:ok resp)
         (throw (ex-info (str "Telegram " method " failed: " (:description resp)) {:method method :params params :status status :resp resp})))
       (:result resp)))))

(def bot-username
  (delay
    (:username (call! "getMe"))))

(defn send-message! [chat-id text]
  (call! "sendMessage"
    {:chat_id chat-id
     :text    text
     :link_preview_options {:is_disabled true}}))

(defn get-updates
  "Long poll, blocks up to timeout-sec"
  [offset timeout-sec]
  (call! "getUpdates"
    {:offset          offset
     :timeout         timeout-sec
     :allowed_updates ["message"]}
    {:timeout (* 1000 (+ timeout-sec 10))}))
