(ns isaac.worksite-steps
  (:require
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.worksite.lock :as lock]))

(helper! isaac.worksite-steps)

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
  isaac.worksite-steps/stale-turn-lock-holds)
