(ns isaac.worksite.lock
  "Durable operator/turn locks for named worksites.
   Files live under <root>/worksites/<name>.lock as EDN maps."
  (:require
    [clojure.edn :as edn]
    [isaac.cli.host :as host]
    [isaac.fs :as fs]
    [isaac.logger :as log]))

(defn- filesystem []
  (or (fs/instance)
      (throw (ex-info "worksite.lock requires :fs" {}))))

(defn lock-dir [root]
  (str root "/worksites"))

(defn lock-path [root name]
  (str (lock-dir root) "/" name ".lock"))

(defn- process-alive? [pid]
  (boolean
    (when (number? pid)
      (try
        (let [opt (java.lang.ProcessHandle/of (long pid))]
          (and (.isPresent opt) (.isAlive (.get opt))))
        (catch Exception _
          false)))))

(defonce ^:private live-owners* (atom #{}))

(defn current-pid []
  (.pid (java.lang.ProcessHandle/current)))

(defn current-owner []
  (str (current-pid) ":" (System/identityHashCode host/*host*)))

(defn- stamp []
  (let [owner (current-owner)]
    (swap! live-owners* conj owner)
    {:pid (current-pid) :owner owner}))

(defn- owner-alive? [record]
  (or (contains? @live-owners* (:owner record))
      (and (some? (:owner record))
           (not= (current-pid) (:pid record))
           (process-alive? (:pid record)))
      (and (nil? (:owner record))
           (process-alive? (:pid record)))))

(defn read-lock [root name]
  (let [fs*  (filesystem)
        path (lock-path root name)]
    (when (fs/exists? fs* path)
      (try
        (edn/read-string (fs/slurp fs* path))
        (catch Exception _
          nil)))))

(defn- write-lock! [root name record]
  (let [fs*  (filesystem)
        path (lock-path root name)]
    (fs/mkdirs fs* (lock-dir root))
    (fs/spit fs* path (pr-str record))
    record))

(defn- delete-lock! [root name]
  (let [fs*  (filesystem)
        path (lock-path root name)]
    (when (fs/exists? fs* path)
      (fs/delete fs* path))
    nil))

(defn locked? [record]
  (boolean record))

(defn operator-lock? [record]
  (= :operator (:kind record)))

(defn turn-lock? [record]
  (= :turn (:kind record)))

(defn stale-turn-lock? [record]
  (and (turn-lock? record)
       (not (owner-alive? record))))

(defn lock-state [record]
  (cond
    (nil? record) :free
    (operator-lock? record) :operator
    (turn-lock? record) :turn
    :else :locked))

(defn acquire-operator!
  "Take an operator lock. Returns {:ok true} or {:error :already-locked}."
  [root name]
  (let [existing (read-lock root name)]
    (cond
      (nil? existing)
      (do
        (write-lock! root name (merge {:kind   :operator
                                       :holder "operator"
                                       :at     (str (java.time.Instant/now))}
                                      (stamp)))
        {:ok true})

      :else
      {:error :already-locked :lock existing})))

(defn release-operator!
  "Drop an operator lock. Returns {:ok true} or {:error :not-locked}."
  [root name]
  (let [existing (read-lock root name)]
    (if (nil? existing)
      {:error :not-locked}
      (do
        (delete-lock! root name)
        {:ok true}))))

(defn steal-stale-turn!
  "If the current lock is a dead-pid turn lock, delete it and log loudly.
   Returns true when a steal happened."
  [root name]
  (let [existing (read-lock root name)]
    (when (stale-turn-lock? existing)
      (log/info :worksite/stale-lock-stolen
                :worksite name
                :pid (:pid existing)
                :session (:session existing))
      (delete-lock! root name)
      true)))

(defn acquire-turn!
  "Take a turn lock for session-key. Steals a stale turn lock first.
   Returns {:ok true :token ...} or {:error :already-locked :lock ...}."
  [root name {:keys [session-key]}]
  (steal-stale-turn! root name)
  (let [existing (read-lock root name)]
    (if existing
      {:error :already-locked :lock existing}
      (let [token (str (java.util.UUID/randomUUID))]
        (write-lock! root name (merge {:kind    :turn
                                       :holder  session-key
                                       :session session-key
                                       :token   token
                                       :at      (str (java.time.Instant/now))}
                                      (stamp)))
        {:ok true :token token}))))

(defn release-turn!
  "Drop a turn lock. No-op if the file is gone or is an operator lock."
  [root name token]
  (let [existing (read-lock root name)]
    (when (and (turn-lock? existing)
               (or (nil? token) (= token (:token existing))))
      (delete-lock! root name))
    nil))
