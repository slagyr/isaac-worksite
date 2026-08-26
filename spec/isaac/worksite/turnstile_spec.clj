(ns isaac.worksite.turnstile-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.turnstile :as turnstile]
    [isaac.worksite.lock :as lock]
    [isaac.worksite.turnstile :as sut]
    [speclj.core :refer [describe it should should-be-nil should=]]))

(describe "worksite turnstile"

  (it "passes when cwd is not a member"
    (let [mem (fs/mem-fs)
          ts  (sut/worksite nil)]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (should= :pass (turnstile/admit? ts {:cwd "/ships/cordelia/bow"
                                             :session-key "open-seas"})))))

  (it "refuses with :worksite-busy when the member is operator-locked"
    (let [mem (fs/mem-fs)
          ts  (sut/worksite ["chart-room"])]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (lock/acquire-operator! "/isaac-state" "chart-room")
        (let [decision (turnstile/admit? ts {:cwd "/ships/cordelia/chart-room"
                                             :session-key "helm-chat"})]
          (should= :worksite-busy (:refuse decision))
          (should= "chart-room locked" (:message decision))))))

  (it "takes a turn lock on admit and releases it"
    (let [mem (fs/mem-fs)
          ts  (sut/worksite ["chart-room"])]
      (nexus/-with-nexus {:fs mem :root "/isaac-state"}
        (let [decision (turnstile/admit? ts {:cwd "/ships/cordelia/chart-room"
                                             :session-key "helm-chat"})]
          (should= :pass (:status decision))
          (should= :turn (lock/lock-state (lock/read-lock "/isaac-state" "chart-room")))
          (turnstile/release! ts (:token decision))
          (should-be-nil (lock/read-lock "/isaac-state" "chart-room"))))))
)
