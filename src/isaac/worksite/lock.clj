(ns isaac.worksite.lock
  "Durable operator/turn locks for named worksites.
   Files live under <root>/worksites/<encoded-member>.lock as EDN maps."
  (:require
    [clojure.edn :as edn]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log])
  (:import (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)))

(defn- filesystem []
  (or (fs/instance)
      (throw (ex-info "worksite.lock requires :fs" {}))))

(defn lock-dir [root]
  (str root "/worksites"))

(defn lock-path [root name]
  (str (lock-dir root) "/" (java.net.URLEncoder/encode (str name) "UTF-8") ".lock"))

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

(defonce ^:private guards* (atom {}))

(defn- guard [path]
  (or (get @guards* path)
      (get (swap! guards* #(if (contains? % path) % (assoc % path (java.util.concurrent.locks.ReentrantLock.)))) path)))

(defn- lock-content [root name]
  (let [fs* (filesystem)
        path (lock-path root name)]
    (when (fs/exists? fs* path)
      (fs/slurp fs* path))))

(defn- undo-written-lock! [root name before]
  (try
    ;; The guard excludes competing acquisitions. Compare raw bytes rather than
    ;; parsed EDN: an interrupted write may leave an unparseable fragment.
    (let [after (lock-content root name)]
      (when (not= before after)
        (if (nil? before)
          (delete-lock! root name)
          ;; A failed replacement may have truncated an older stale lease.
          ;; Restore the original bytes without using the failing fs/spit seam.
          (clojure.core/spit (lock-path root name) before))))
    (catch Exception cleanup
      (log/warn :worksite/cleanup-failed :worksite name :error (.getMessage cleanup)))))

(defn- with-guard [root name busy f]
  (let [path (lock-path root name)
        mutex (guard path)]
    (if-not (.tryLock mutex)
      busy
      (try
        (if (instance? isaac.foundation.fs.RealFs (filesystem))
          (let [file (java.io.File. (str path ".guard"))]
            (.mkdirs (.getParentFile file))
            (with-open [channel (FileChannel/open (.toPath file)
                           (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
              (try
                (if-let [lock (try (.tryLock channel) (catch Exception _ nil))]
                  (let [before (lock-content root name)
                        result (try (f)
                                    (catch Exception e
                                      (undo-written-lock! root name before)
                                      (log/warn :worksite/guard-failed :worksite name :error (.getMessage e))
                                      busy))]
                    (try (.release lock)
                         (catch Exception e
                           (log/warn :worksite/guard-failed :worksite name :error (.getMessage e))))
                    result)
                  busy)
                (catch Exception e
                  (log/warn :worksite/guard-failed :worksite name :error (.getMessage e))
                  busy))))
          (f))
        (finally (.unlock mutex))))))

(defn acquire-operator!
  "Atomically claim one member for an operator."
  [root name]
  (with-guard root name {:error :already-locked}
    #(if-let [existing (read-lock root name)]
       {:error :already-locked :lock existing}
       (do (write-lock! root name (merge {:kind :operator :holder "operator"
                                           :at (str (java.time.Instant/now))} (stamp)))
           {:ok true}))))

(defn release-operator! [root name]
  (with-guard root name {:error :not-locked}
    #(if (operator-lock? (read-lock root name))
       (do (delete-lock! root name) {:ok true})
       {:error :not-locked})))

(defn steal-stale-turn! [root name]
  (with-guard root name false
    #(let [existing (read-lock root name)]
       (when (stale-turn-lock? existing)
         (log/info :worksite/stale-lock-stolen :worksite name :pid (:pid existing) :session (:session existing))
         (delete-lock! root name)
         true))))

(defn acquire-turn! [root name {:keys [session-key]}]
  (with-guard root name {:error :already-locked}
    #(let [existing (read-lock root name)]
       (if (and existing (not (stale-turn-lock? existing)))
         {:error :already-locked :lock existing}
         (let [token (str (java.util.UUID/randomUUID))]
           (when existing
             (log/info :worksite/stale-lock-stolen :worksite name :pid (:pid existing) :session (:session existing)))
           (write-lock! root name (merge {:kind :turn :holder session-key :session session-key
                                          :token token :at (str (java.time.Instant/now))} (stamp)))
           {:ok true :token token})))))

(defn release-turn! [root name token]
  (with-guard root name nil
    #(let [existing (read-lock root name)]
       (when (and (turn-lock? existing) (= token (:token existing)))
         (delete-lock! root name)))))
