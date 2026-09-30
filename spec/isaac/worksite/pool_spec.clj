(ns isaac.worksite.pool-spec
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.resource-pool :as pool]
    [isaac.worksite.pool :as sut]
    [speclj.core :refer [describe it should=]]))

(describe "worksite pool"
  (it "leases free directories in order, and returns a freed member"
    (nexus/-with-nexus {:fs (fs/mem-fs) :root "/isaac-state"}
      (let [p (sut/worksite {:members ["/decks/chart" "/decks/galley"]})
            a (pool/try-acquire p {:session-key "harbor"})
            b (pool/try-acquire p {:session-key "jetty"})]
        (should= "/decks/chart" (get-in a [:bindings :session/cwd]))
        (should= "/decks/galley" (get-in b [:bindings :session/cwd]))
        (should= :busy (pool/try-acquire p {:session-key "quay"}))
        (pool/release! p b)
        (should= "/decks/galley" (get-in (pool/try-acquire p {:session-key "quay"}) [:bindings :session/cwd])))))
  )
