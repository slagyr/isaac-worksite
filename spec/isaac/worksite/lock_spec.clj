(ns isaac.worksite.lock-spec
  (:require
    [clojure.java.io :as io]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]
    [isaac.worksite.lock :as sut]
    [speclj.core :refer [describe it should should-be-nil should-not should-not= should=]]))

(describe "worksite lock"

  (it "removes its own real-filesystem lock and warns when acquisition fails after writing"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "worksite-lock-spec"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
          root (.getPath dir)
          spit* fs/spit]
      (try
        (nexus/-with-nexus {:fs (fs/real-fs) :root root}
          (log/capture-logs
            (let [result (with-redefs [fs/spit (fn [filesystem path value]
                                                 (spit* filesystem path value)
                                                 (throw (ex-info "disk interrupted" {})))]
                           (sut/acquire-turn! root "chart-room" {:session-key "harbor"}))]
            (should= :already-locked (:error result))
            (should-be-nil (sut/read-lock root "chart-room"))
            (should (some #(and (= :worksite/guard-failed (:event %))
                                (= "disk interrupted" (:error %))) @log/captured-logs)))))
        (clojure.java.io/delete-file (sut/lock-path root "chart-room") true)
        (clojure.java.io/delete-file (str (sut/lock-path root "chart-room") ".guard") true)
        (clojure.java.io/delete-file (str root "/worksites") true)
        (clojure.java.io/delete-file dir true))))

  (it "reports a successful real-filesystem lease as acquired"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "worksite-success-spec"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
          root (.getPath dir)]
      (try
        (nexus/-with-nexus {:fs (fs/real-fs) :root root}
          (log/capture-logs
            (let [claim (sut/acquire-turn! root "chart-room" {:session-key "harbor"})]
              (should (:ok claim))
              (sut/release-turn! root "chart-room" (:token claim)))))
        (io/delete-file (sut/lock-path root "chart-room") true)
        (io/delete-file (str (sut/lock-path root "chart-room") ".guard") true)
        (io/delete-file (str root "/worksites") true)
        (io/delete-file dir true))))

  (it "starts free"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (should-be-nil (sut/read-lock "/isaac-state" "chart-room"))
        (should= :free (sut/lock-state nil)))))

  (it "takes and releases an operator lock"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (should (:ok (sut/acquire-operator! "/isaac-state" "chart-room")))
        (should= :operator (sut/lock-state (sut/read-lock "/isaac-state" "chart-room")))
        (should= :already-locked (:error (sut/acquire-operator! "/isaac-state" "chart-room")))
        (should (:ok (sut/release-operator! "/isaac-state" "chart-room")))
        (should-be-nil (sut/read-lock "/isaac-state" "chart-room"))
        (should= :not-locked (:error (sut/release-operator! "/isaac-state" "chart-room"))))))

  (it "takes a turn lock and refuses a second take"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (let [first* (sut/acquire-turn! "/isaac-state" "chart-room" {:session-key "helm-chat"})]
          (should (:ok first*))
          (should= :turn (sut/lock-state (sut/read-lock "/isaac-state" "chart-room")))
          (should= :already-locked (:error (sut/acquire-turn! "/isaac-state" "chart-room"
                                                              {:session-key "other"})))
          (sut/release-turn! "/isaac-state" "chart-room" (:token first*))
          (should-be-nil (sut/read-lock "/isaac-state" "chart-room"))))))

  (it "never grants the same member to two simultaneous claimants"
    (let [mem (fs/mem-fs)
          go  (promise)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (let [attempts (doall (repeatedly 2 #(future @go (sut/acquire-turn! "/isaac-state" "/ships/cordelia/galley" {:session-key "harbor"}))))]
          (deliver go true)
          (should= 1 (count (filter :ok (map deref attempts))))))))

  (it "does not treat an operator lock as stealable"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (sut/acquire-operator! "/isaac-state" "chart-room")
        (should-not (sut/steal-stale-turn! "/isaac-state" "chart-room"))
        (should= :operator (sut/lock-state (sut/read-lock "/isaac-state" "chart-room"))))))

  (it "stamps an owner id that is more than a bare pid"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (should (:ok (sut/acquire-turn! "/isaac-state" "chart-room" {:session-key "helm-chat"})))
        (let [record (sut/read-lock "/isaac-state" "chart-room")]
          (should= (sut/current-pid) (:pid record))
          (should-not (nil? (:owner record)))
          (should-not= (str (:pid record)) (str (:owner record)))))))

  (it "does not treat an embedded operator lock and a server turn lock in the same pid as the same owner"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (let [embedded (host/embedded-host {:in  (java.io.StringReader. "")
                                            :out (java.io.StringWriter.)
                                            :err (java.io.StringWriter.)
                                            :env {}
                                            :cwd "/test/isaac"})]
          (binding [host/*host* embedded]
            (should (:ok (sut/acquire-operator! "/isaac-state" "chart-room"))))
          (let [operator (sut/read-lock "/isaac-state" "chart-room")]
            (should (:ok (sut/release-operator! "/isaac-state" "chart-room")))
            (should (:ok (sut/acquire-turn! "/isaac-state" "chart-room" {:session-key "helm-chat"})))
            (let [turn (sut/read-lock "/isaac-state" "chart-room")]
              (should= (:pid operator) (:pid turn))
              (should-not= (:owner operator) (:owner turn))))))))
  )
