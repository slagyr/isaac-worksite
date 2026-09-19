(ns isaac.worksite.lock-spec
  (:require
    [isaac.cli.host :as host]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.worksite.lock :as sut]
    [speclj.core :refer [describe it should should-be-nil should-not should-not= should=]]))

(describe "worksite lock"

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
