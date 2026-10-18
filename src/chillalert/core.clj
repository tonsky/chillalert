(ns chillalert.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [mount.core :as mount])
  (:import
   [java.time Instant LocalDate LocalDateTime LocalTime ZoneId ZonedDateTime]
   [java.time.format DateTimeFormatter]
   [java.time.temporal ChronoUnit]
   [java.util Date Locale Timer TimerTask]))

;; Config

(def config-spec
  "[key default parse]. Each key is read from `--key value` CLI arg,
   then KEY env var, then falls back to default"
  [[:base-url        "http://localhost:8080"]
   [:port            8080 parse-long]
   [:data-dir        "data"]
   [:db-file         "chillalert.sqlite"]
   [:tg-token        nil]
   [:tmdb-read-token nil]
   [:forced-user     nil parse-long]]) ; Telegram id, dev only: skips login and Telegram polling

(defn read-config [args]
  (let [flags   (into {} (for [[key] config-spec] [(str "--" (name key)) key]))
        cli     (into {} (map vec) (partition 2 args))
        unknown (remove flags (keys cli))]
    (when (seq unknown)
      (throw (ex-info (str "Unknown arguments: " (str/join ", " unknown) ", expected: " (str/join ", " (keys flags))) {})))
    (let [config (into {}
                   (for [[key default parse] config-spec
                         :let [env   (-> (name key) str/upper-case (str/replace "-" "_"))
                               value (or (get cli (str "--" (name key))) (System/getenv env))]]
                     [key (if (some? value) ((or parse identity) value) default)]))]
      (assoc config :dev? (str/starts-with? (:base-url config) "http://localhost")))))

(mount/defstate config
  :start (read-config (mount/args)))

(defn data-file ^java.io.File [& path]
  (apply io/file (:data-dir config) path))

;; Utils

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

(defn ms->date
  "Millisecond timestamp to UTC LocalDate"
  ^LocalDate [ms]
  (-> (Instant/ofEpochMilli ms) (.atZone UTC) (.toLocalDate)))

(defn days-between
  "Absolute distance between two dates in days"
  ^long [^LocalDate a ^LocalDate b]
  (abs (.between ChronoUnit/DAYS a b)))

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
  :start (Timer. "chillalert-timer" true)
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
