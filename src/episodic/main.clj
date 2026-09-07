(ns episodic.main
  (:require
   [episodic.auth]
   [episodic.core]
   [episodic.daily]
   [episodic.db]
   [episodic.server]
   [mount.core :as mount]))

(defn -main [& _]
  (mount/start)
  @(promise))
