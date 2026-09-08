(ns episodic.core
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [mount.core :as mount])
  (:import
   [java.time LocalDate LocalDateTime LocalTime ZoneId ZonedDateTime]
   [java.time.format DateTimeFormatter]
   [java.util Date Locale Timer TimerTask]))

(def config
  (edn/read-string (slurp "config.edn")))

(def hostname
  (:hostname config))

(def dev?
  (str/starts-with? hostname "http://localhost"))

(def storage
  "data")

(.mkdirs (io/file storage "posters"))

(def ^ZoneId UTC
  (ZoneId/of "UTC"))

(def lock
  (Object.))

(let [out *out*]
  (defn log [& args]
    (locking lock
      (binding [*out* out]
        (println (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss") (LocalDateTime/now UTC)) (str/join " " args))))))

(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log "Uncaught exception on" (.getName ^Thread thread))
      (.printStackTrace ^Throwable ex))))

(defn now ^long []
  (System/currentTimeMillis))

(defn today ^LocalDate []
  (LocalDate/now UTC))

(defn parse-date ^LocalDate [s]
  (when-not (str/blank? s)
    (LocalDate/parse s)))

(def ^DateTimeFormatter month-day
  (-> (DateTimeFormatter/ofPattern "MMM d") (.withLocale Locale/US)))

(def ^DateTimeFormatter month-day-year
  (-> (DateTimeFormatter/ofPattern "MMM d, yyyy") (.withLocale Locale/US)))

(defn format-date
  "Sep 10, or Sep 10, 2027 when the year differs from the current one"
  [^LocalDate date]
  (if (= (.getYear date) (.getYear (today)))
    (.format month-day date)
    (.format month-day-year date)))

(defn some-map [& args]
  (persistent!
    (reduce (fn [m [k v]] (if (some? v) (assoc! m k v) m))
      (transient {})
      (partition 2 args))))

(defn map-by [f xs]
  (into {} (map #(vector (f %) %)) xs))

(defn random-token
  "URL-safe random string"
  [bytes]
  (let [seed (byte-array bytes)]
    (.nextBytes (java.security.SecureRandom.) seed)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) seed)))

;; Timer

(mount/defstate ^Timer timer
  :start (Timer. "episodic-timer" true)
  :stop  (.cancel ^Timer timer))

(defn- timer-task ^TimerTask [f]
  (proxy [TimerTask] []
    (run []
      (try
        (f)
        (catch Throwable t
          (log "Timer task failed")
          (.printStackTrace t))))))

(defn schedule-at
  "Runs f once at given instant"
  ^TimerTask [f ^ZonedDateTime at]
  (let [task (timer-task f)]
    (.schedule timer task (Date/from (.toInstant at)))
    task))

(defn next-time-at
  "Next occurence of hour:00 UTC strictly after now"
  ^ZonedDateTime [hour]
  (let [now  (ZonedDateTime/now UTC)
        at   (.with now (LocalTime/of hour 0))]
    (if (.isAfter at now)
      at
      (.plusDays at 1))))

(defn before-ns-unload []
  (mount/stop #'timer))
