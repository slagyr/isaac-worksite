(ns isaac.worksite.pool
  "Named directory pool; a receipt reserves exactly one member."
  (:require
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.resource-pool :as pool]
    [isaac.worksite.lock :as lock]))

(defn- isaac-root []
  (or (nexus/get :root) (loader/root) (root/current-root) (root/default-root)))

(defn worksite [{:keys [members]}]
  (reify pool/ResourcePool
    (try-acquire [_ ctx]
      (let [root (isaac-root)]
        (or (some (fn [member]
                    (let [claim (lock/acquire-turn! root member {:session-key (:session-key ctx)})]
                      (when (:ok claim)
                        {:bindings   {:session/cwd member}
                         :release-id (str member "|" (:token claim))})))
                  members)
            :busy)))
    (release! [_ receipt]
      (let [id (or (:release-id receipt) (:id receipt))
            [member token] (clojure.string/split id #"\|" 2)]
        (when token (lock/release-turn! (isaac-root) member token))))))
