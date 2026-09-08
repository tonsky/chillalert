(ns chillalert.package
  (:require
   [clojure.edn :as edn]
   [uberdeps.api :as uberdeps]))

(defn -main [& _]
  (binding [uberdeps/level :debug]
    (uberdeps/package
      (edn/read-string (slurp "deps.edn"))
      "target/chillalert.jar"
      {:exclusions [#"\.DS_Store"]}))
  (shutdown-agents))
