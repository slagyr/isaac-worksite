(ns isaac.worksite.registry
  "Worksite config lookup: named pools of member directories."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]))

(defn- snapshot []
  (or (loader/snapshot "worksite registry") {}))

(defn all
  "Map of worksite name (string) -> {:members [...]} from the current config."
  ([] (all (snapshot)))
  ([cfg]
   (into {}
         (keep (fn [[k v]]
                 (when (and (map? v) (seq (:members v)))
                   [(name k) {:members (mapv str (:members v))}])))
         (or (:worksites cfg) {}))))

(defn names
  ([] (names (snapshot)))
  ([cfg] (sort (keys (all cfg)))))

(defn lookup
  ([name] (lookup (snapshot) name))
  ([cfg name]
   (get (all cfg) (str name))))

(defn- normalize-path [path]
  (when path
    (-> path str
        (str/replace #"\\+" "/")
        (str/replace #"/+" "/")
        (#(if (and (> (count %) 1) (str/ends-with? % "/"))
            (subs % 0 (dec (count %)))
            %)))))

(defn member-of
  "Return the worksite name whose :members contain cwd (exact match), else nil."
  ([cwd] (member-of (snapshot) cwd))
  ([cfg cwd]
   (let [cwd* (normalize-path cwd)]
     (when cwd*
       (some (fn [[name {:keys [members]}]]
               (when (some #(= cwd* (normalize-path %)) members)
                 name))
             (all cfg))))))
