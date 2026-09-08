(ns chillalert.main
  (:require
   [chillalert.auth]
   [chillalert.core]
   [chillalert.daily]
   [chillalert.db]
   [chillalert.server]
   [mount.core :as mount]))

(defn -main [& _]
  (mount/start)
  @(promise))
