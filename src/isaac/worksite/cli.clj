(ns isaac.worksite.cli
  (:require
    [clojure.string :as str]
    [isaac.cli.api :as cli-api]
    [isaac.cli.registry :as cli]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.worksite.lock :as lock]
    [isaac.worksite.registry :as registry]))

(defn- derive-root [opts]
  (or (:root opts)
      (nexus/get :root)
      (root/current-root)
      (root/default-root opts)))

(defn- load-cfg [root]
  (let [fs* (or (fs/instance) (fs/real-fs))]
    (try
      (:config (loader/load-config-result {:root root :fs fs*}))
      (catch Exception _
        (or (loader/snapshot "worksite cli") {})))))

(defn- state-label [record]
  (case (lock/lock-state record)
    :free     "free"
    :operator "locked (operator)"
    :turn     "locked (turn)"
    "locked"))

(defn- list-rows [root cfg]
  (mapv (fn [name]
          (let [site   (registry/lookup cfg name)
                record (lock/read-lock root name)]
            {:name    name
             :state   (state-label record)
             :members (str/join " " (or (:members site) []))}))
        (registry/names cfg)))

(defn- format-list [rows]
  (str/join "\n"
            (map (fn [{:keys [name state members]}]
                   (str name " " state " " members))
                 rows)))

(defn- run-list [opts]
  (let [root (derive-root opts)
        cfg  (load-cfg root)
        rows (list-rows root cfg)]
    (when (seq rows)
      (println (format-list rows)))
    0))

(defn- known-name? [cfg name]
  (boolean (registry/lookup cfg name)))

(defn- run-lock [opts name]
  (let [root (derive-root opts)
        cfg  (load-cfg root)]
    (cond
      (str/blank? name)
      (do (binding [*out* *err*] (println "Usage: isaac worksites lock <name>")) 1)

      (not (known-name? cfg name))
      (do (binding [*out* *err*] (println (str "unknown worksite: " name))) 1)

      :else
      (let [result (lock/acquire-operator! root name)]
        (if (:ok result)
          (do (println (str "locked " name)) 0)
          (do (binding [*out* *err*] (println (str name " already locked"))) 1))))))

(defn- run-unlock [opts name]
  (let [root (derive-root opts)
        cfg  (load-cfg root)]
    (cond
      (str/blank? name)
      (do (binding [*out* *err*] (println "Usage: isaac worksites unlock <name>")) 1)

      (not (known-name? cfg name))
      (do (binding [*out* *err*] (println (str "unknown worksite: " name))) 1)

      :else
      (let [result (lock/release-operator! root name)]
        (if (:ok result)
          (do (println (str "unlocked " name)) 0)
          (do (binding [*out* *err*] (println (str name " not locked"))) 1))))))

(defn- print-help! []
  (println (cli/command-help (cli/get-command "worksites")))
  0)

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [raw-args (or _raw-args [])
        subcmd   (first raw-args)]
    (cond
      (or (nil? subcmd) (= "list" subcmd) (= "--help" subcmd) (= "-h" subcmd))
      (if (or (= "--help" subcmd) (= "-h" subcmd))
        (print-help!)
        (run-list opts))

      (= "lock" subcmd)
      (run-lock opts (second raw-args))

      (= "unlock" subcmd)
      (run-unlock opts (second raw-args))

      :else
      (do
        (binding [*out* *err*]
          (println (str "Unknown worksites subcommand: " subcmd)))
        1))))

(defmethod cli-api/run :worksites [_id opts]
  (run-fn opts))

(defmethod cli-api/subcommands :worksites [_id]
  [{:name "list"   :summary "List worksites and their lock state"}
   {:name "lock"   :summary "Take an operator lock on a worksite"}
   {:name "unlock" :summary "Release an operator lock on a worksite"}])
