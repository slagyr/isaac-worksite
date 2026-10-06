(ns isaac.worksite.worksite-steps
  (:require
    [clojure.edn :as edn]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.foundation.config.config-steps :as config-steps]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.resource-pool :as pool]
    [isaac.agent.session.store.memory :as memory-store]
    [isaac.agent.session.store.spi :as store]
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

(defn real-worksite-locks []
  ;; Copy the in-memory fixture to a disposable real root before running the
  ;; CLI. The feature's Background seeds config, models and sessions in MemFs.
  (let [root (str (java.nio.file.Files/createTempDirectory "worksite-feature-"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        mem  (g/get :mem-fs)
        original-root (g/get :root)
        old-store (store/registered-store)]
    (letfn [(copy-dir! [source destination]
              (.mkdirs (java.io.File. destination))
              (doseq [child (fs/children mem source)]
                (let [src (str source "/" child)
                      dst (str destination "/" child)]
                  (if (and (fs/dir? mem src) (not (fs/file? mem src)))
                    (copy-dir! src dst)
                    (do (.mkdirs (java.io.File. destination))
                        (clojure.core/spit dst (fs/slurp mem src)))))))]
      (copy-dir! original-root root))
    (g/dissoc! :mem-fs)
    (g/assoc! :root root)
    (g/assoc! :runtime-root-dir root)
    (nexus/register! [:root] root)
    (nexus/register! [:fs] (fs/real-fs))
    (let [session-store (memory-store/create-store nil)]
      (doseq [session (store/list-sessions old-store)]
        (store/open-session! session-store (:name session)
                             {:crew (:crew session) :cwd (:cwd session)}))
      (store/register-store! session-store))))

(defn partial-lock-write [member]
  (let [spit* fs/spit]
    (cli-steps/register-isaac-run-wrapper!
      (fn [run]
        (with-redefs [fs/spit (fn [filesystem path content & opts]
                               (if (and (.endsWith (str path) (str "/" (java.net.URLEncoder/encode (clojure.string/replace member #"^\"|\"$" "") "UTF-8") ".lock"))
                                        (instance? isaac.foundation.fs.RealFs filesystem))
                                 (do (spit* filesystem path "{:kind :turn")
                                     (throw (ex-info "disk interrupted" {})))
                                 (apply spit* filesystem path content opts)))]
          (run))))))

(defgiven "the worksite locks live on the real filesystem"
  isaac.worksite.worksite-steps/real-worksite-locks)

(defgiven "writing the lock for {string} fails partway"
  isaac.worksite.worksite-steps/partial-lock-write)

(defgiven "a stale turn lock holds worksite {string} with pid {int}"
  isaac.worksite.worksite-steps/stale-turn-lock-holds)
