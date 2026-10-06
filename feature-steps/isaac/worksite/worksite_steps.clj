(ns isaac.worksite.worksite-steps
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.foundation.config.config-steps :as config-steps]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.agent.resource-pool :as pool]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.session.store.memory :as memory-store]
    [isaac.worksite.pool :as worksite-pool]
    [isaac.worksite.lock :as lock]))

(helper! isaac.worksite.worksite-steps)

(g/before-scenario (fn [] (pool/register! :worksite worksite-pool/worksite)))

(g/after-scenario
  (fn []
    (when-let [dir (g/get :worksite-real-root)]
      (doseq [file (reverse (file-seq (io/file dir)))]
        (io/delete-file file true)))))

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

(defn real-worksite-locks []
  ;; The Background seeds everything in MemFs. Stage the exact fixture into
  ;; an isolated real root so CLI and the worksite guard share the same disk.
  (let [old-root (feature-root)
        new-root (str (java.nio.file.Files/createTempDirectory "worksite-feature-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        mem      (g/get :mem-fs)]
    ;; MemFs stores absolute paths; recreate its tree under the new root.
    (letfn [(copy! [source target]
              (if (fs/file? mem source)
                (do (fs/mkdirs (fs/real-fs) (fs/parent target))
                    (fs/spit (fs/real-fs) target (fs/slurp mem source)))
                (do (fs/mkdirs (fs/real-fs) target)
                    (doseq [child (fs/children mem source)]
                      (copy! (str source "/" child) (str target "/" child))))))]
      (copy! old-root new-root))
    (g/assoc! :worksite-real-root new-root)
    (g/dissoc! :mem-fs)
    (g/assoc! :root new-root)
    (nexus/register! [:fs] (fs/real-fs))
    (nexus/register! [:root] new-root)
    (store/register-store! (memory-store/create-store new-root))
    (cli-steps/register-isaac-run-wrapper!
      (fn [thunk]
        (if (g/get :worksite-failing-member)
          (let [spit* fs/spit
                path  (lock/lock-path new-root (g/get :worksite-failing-member))]
            (with-redefs [fs/spit (fn [filesystem file value & options]
                                   (if (= path file)
                                     (do (spit* filesystem file "{:kind :turn")
                                         (throw (ex-info "disk interrupted" {})))
                                     (apply spit* filesystem file value options)))]
              (thunk)))
          (thunk))))))

(defn fail-lock-partway [member]
  (g/assoc! :worksite-failing-member (edn/read-string member)))

(defgiven "the worksite locks live on the real filesystem"
  isaac.worksite.worksite-steps/real-worksite-locks)
(defgiven "writing the lock for {string} fails partway"
  isaac.worksite.worksite-steps/fail-lock-partway)
