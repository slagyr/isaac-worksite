(ns isaac.worksite.worksite-steps
  (:require
    [clojure.edn :as edn]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.foundation.config.config-steps :as config-steps]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.resource-pool :as pool]
    [isaac.worksite.pool :as worksite-pool]
    [isaac.worksite.lock :as lock]))

(helper! isaac.worksite.worksite-steps)

(g/before-scenario (fn [] (pool/register! :worksite worksite-pool/worksite)))

;; A partial root config replaces default Grover setup's root defaults. Preserve
;; the model fixture when a scenario names a worksite pool; the production
;; loader still reads the real config and supplies the lease.
(defonce ^:private fixture-config-installed?
  (do
    (alter-var-root #'config-steps/config-file-containing
      (fn [original]
        (fn [path content]
          (let [root (or (g/get :runtime-root-dir) (g/get :root))
                fs*  (or (g/get :mem-fs) (fs/instance))
                cfg  (when (= "isaac.edn" path)
                       (try (edn/read-string content) (catch Exception _ nil)))
                grover? (and root fs* (fs/exists? fs* (str root "/config/models/grover.edn")))]
            (original path
                      (if (and grover? (:resource-pools cfg))
                        (pr-str (assoc-in cfg [:defaults :crew :model] "grover"))
                        content))))))
    true))


(defn- feature-root []
  (or (nexus/get :root) (root/default-root)))

(defn stale-turn-lock-holds [name pid-str]
  (let [root (feature-root)
        fs*  (or (fs/instance) (fs/real-fs))
        path (lock/lock-path root name)]
    (fs/mkdirs fs* (lock/lock-dir root))
    (fs/spit fs* path (pr-str {:kind    :turn
                               :holder  "stale"
                               :session "stale"
                               :pid     (parse-long pid-str)
                               :token   "stale-token"
                               :at      (str (java.time.Instant/now))}))
    nil))

(defgiven "a stale turn lock holds worksite {string} with pid {int}"
  isaac.worksite.worksite-steps/stale-turn-lock-holds)
