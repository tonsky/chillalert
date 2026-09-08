(ns user
  (:require
   [clj-reload.core :as clj-reload]
   [clojure+.hashp :as hashp]
   [clojure+.print :as print]
   [clojure+.error :as error]
   [mount.core :as mount]))

(hashp/install!)
(print/install!)
(error/install!)

(clj-reload/init
  {:dirs      ["src" "dev"]
   :no-reload '#{user}})

(defn start []
  (require 'chillalert.main)
  (mount/start)
  :started)

(defn stop []
  (mount/stop)
  :stopped)

(defn reload
  "Stops changed mount states, reloads changed namespaces, starts everything"
  [& [opts]]
  (set! *warn-on-reflection* true)
  (let [res (clj-reload/reload opts)]
    (start)
    (str "Reloaded " (count (:loaded res)) " nses")))

(start)