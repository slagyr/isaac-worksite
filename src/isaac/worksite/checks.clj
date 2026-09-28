(ns isaac.worksite.checks
  "Validate worksite pool members and reject the removed config key.")

(defn check-worksite-members [{:keys [config result]}]
  {:errors (vec
             (concat
               (when (or (contains? config :worksites) (contains? (:root result) :worksites))
                 [{:key "worksites" :value "worksites removed; configure resource-pools"}])
               (mapcat (fn [[id site]]
                         (when (= :worksite (:type site))
                           (let [path (str "resource-pools." (name id) ".members")]
                             (concat
                               (when (empty? (:members site))
                                 [{:key path :value (str (name id) " members required")}])
                               (when (some #(or (not (string? %))
                                                (not (.isAbsolute (java.io.File. (str %))))) (:members site))
                                 [{:key path :value (str (name id) " members must be absolute paths")}])))))
                       (:resource-pools config))))
   :warnings []})
