(ns isaac.worksite.lock-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.worksite.lock :as sut]
    [speclj.core :refer [describe it should should-be-nil should-not should=]]))

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
)
