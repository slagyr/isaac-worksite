(ns isaac.worksite.checks
  "Post-load config check: every worksite must declare :members.")

(defn check-worksite-members
  [{:keys [config]}]
  (let [worksites (or (:worksites config) {})]
    {:errors   (vec
                 (keep (fn [[id site]]
                         (when (or (not (map? site))
                                   (empty? (:members site)))
                           {:key   (str "worksites." (name id) ".members")
                            :value (str (name id) " members required")}))
                       worksites))
     :warnings []}))
