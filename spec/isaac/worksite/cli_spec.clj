(ns isaac.worksite.cli-spec
  (:require
    [clojure.edn :as edn]
    [isaac.cli.host :as host]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.worksite.cli :as sut]
    [isaac.worksite.lock :as lock]
    [speclj.core :refer [around describe it should-contain should=]]))

(describe "worksite cli"

  (it "lists root-config worksites"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (fs/mkdirs mem "/isaac-state/config")
        (fs/spit mem "/isaac-state/config/isaac.edn"
                 "{:resource-pools {\"decks\" {:type :worksite :members [\"/ships/cordelia/chart-room\"]}}}")
        (let [exit* (atom nil)
              out   (with-out-str
                      (reset! exit* (sut/run-fn {:root "/isaac-state"
                                                 :_raw-args ["list"]})))]
          (should= 0 @exit*)
          (should-contain "decks /ships/cordelia/chart-room free" out)))))

  (it "locks a root-config worksite by name"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (fs/mkdirs mem "/isaac-state/config")
        (fs/spit mem "/isaac-state/config/isaac.edn"
                 "{:resource-pools {\"decks\" {:type :worksite :members [\"/ships/cordelia/chart-room\"]}}}")
        (let [exit* (atom nil)
              out   (with-out-str
                      (reset! exit* (sut/run-fn {:root "/isaac-state"
                                                 :_raw-args ["lock" "/ships/cordelia/chart-room"]})))]
          (should= 0 @exit*)
          (should-contain "locked /ships/cordelia/chart-room" out)
          (should= :operator (lock/lock-state (lock/read-lock "/isaac-state" "/ships/cordelia/chart-room")))))))

  (it "shows a leased chart-room and operator-locked galley independently"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (fs/mkdirs mem "/isaac-state/config")
        (fs/spit mem "/isaac-state/config/isaac.edn"
                 "{:resource-pools {\"decks\" {:type :worksite :members [\"/ships/cordelia/chart-room\" \"/ships/cordelia/galley\"]}}}")
        (lock/acquire-turn! "/isaac-state" "/ships/cordelia/chart-room" {:session-key "harbor"})
        (lock/acquire-operator! "/isaac-state" "/ships/cordelia/galley")
        (let [out (with-out-str (sut/run-fn {:root "/isaac-state" :_raw-args ["list"]}))]
          (should-contain "decks /ships/cordelia/chart-room leased (harbor)" out)
          (should-contain "decks /ships/cordelia/galley locked (operator)" out)))))

  (it "declares the worksites command hosted"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should= true (get-in manifest [:isaac/cli :worksites :hosted]))))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "runs worksites list via the embedded host without mutating ambient runtime"
    (registry/register! {:name "worksites" :hosted true :run-fn sut/run-fn})
    (let [before-nexus (nexus/necho)
          before-memo  (config-api/process-memo-snapshot)
          out          (java.io.StringWriter.)
          err          (java.io.StringWriter.)
          exit         (host/run-embedded {:argv ["worksites" "list"]
                                           :in   (java.io.StringReader. "")
                                           :out  out
                                           :err  err
                                           :env  {}
                                           :cwd  "/test/isaac"
                                           :root "/test/isaac"})]
      (should= 0 exit)
      (should= before-nexus (nexus/necho))
      (should= before-memo (config-api/process-memo-snapshot))))
  )
