(ns isaac.worksite.cli
  (:require
    [clojure.string :as str]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.registry :as cli]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
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
    :turn     (str "leased (" (:session record) ")")
    "locked"))

(defn- list-rows [root cfg]
  (for [name (registry/names cfg)
        member (:members (registry/lookup cfg name))]
    {:name name :member member :state (state-label (lock/read-lock root member))}))

(defn- format-list [rows]
  (str/join "\n" (map (fn [{:keys [name member state]}]
                         (str name " " member " " state)) rows)))

(defn- run-list [opts]
  (let [root (derive-root opts)
        cfg  (load-cfg root)
        rows (list-rows root cfg)]
    (when (seq rows)
      (println (format-list rows)))
    0))

(defn- known-name? [cfg name]
  (boolean (some (fn [[_ {:keys [members]}]] (some #{name} members)) (registry/all cfg))))

(defn- run-lock [opts name]
  (let [root (derive-root opts)
        cfg  (load-cfg root)]
    (cond
      (str/blank? name)
      (do (binding [*out* *err*] (println "Usage: isaac worksites lock <member-path>")) 1)

      (not (known-name? cfg name))
      (do (binding [*out* *err*] (println (str "not a worksite member: " name))) 1)

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
      (do (binding [*out* *err*] (println "Usage: isaac worksites unlock <member-path>")) 1)

      (not (known-name? cfg name))
      (do (binding [*out* *err*] (println (str "not a worksite member: " name))) 1)

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
  [{:name "list"   :summary "List worksite members and their state"}
   {:name "lock"   :summary "Take an operator lock on a worksite"}
   {:name "unlock" :summary "Release an operator lock on a worksite"}])
