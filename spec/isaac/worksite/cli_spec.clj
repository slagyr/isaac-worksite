(ns isaac.worksite.cli-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.worksite.cli :as sut]
    [isaac.worksite.lock :as lock]
    [speclj.core :refer [describe it should-contain should=]]))

(describe "worksite cli"

  (it "lists root-config worksites"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (fs/mkdirs mem "/isaac-state/config")
        (fs/spit mem "/isaac-state/config/isaac.edn"
                 "{:worksites {\"chart-room\" {:members [\"/ships/cordelia/chart-room\"]}}}")
        (let [exit* (atom nil)
              out   (with-out-str
                      (reset! exit* (sut/run-fn {:root "/isaac-state"
                                                 :_raw-args ["list"]})))]
          (should= 0 @exit*)
          (should-contain "chart-room free /ships/cordelia/chart-room" out)))))

  (it "locks a root-config worksite by name"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (fs/mkdirs mem "/isaac-state/config")
        (fs/spit mem "/isaac-state/config/isaac.edn"
                 "{:worksites {\"chart-room\" {:members [\"/ships/cordelia/chart-room\"]}}}")
        (let [exit* (atom nil)
              out   (with-out-str
                      (reset! exit* (sut/run-fn {:root "/isaac-state"
                                                 :_raw-args ["lock" "chart-room"]})))]
          (should= 0 @exit*)
          (should-contain "locked chart-room" out)
          (should= :operator (lock/lock-state (lock/read-lock "/isaac-state" "chart-room")))))))
  )
