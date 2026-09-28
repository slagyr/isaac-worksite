(ns isaac.worksite.registry-spec
  (:require
    [isaac.worksite.registry :as sut]
    [speclj.core :refer [describe it should-be-nil should=]]))

(describe "worksite registry"

  (it "lists worksites from a config map"
    (let [cfg {:resource-pools {"decks" {:type :worksite :members ["/ships/cordelia/chart-room"]}
                           "galley" {:type :worksite :members ["/ships/cordelia/galley"]}}}]
      (should= ["decks" "galley"] (sut/names cfg))
      (should= {:type :worksite :members ["/ships/cordelia/chart-room"]}
               (sut/lookup cfg "decks"))))

  (it "matches a cwd to its worksite member"
    (let [cfg {:resource-pools {"decks" {:type :worksite :members ["/ships/cordelia/chart-room"]}}}]
      (should= "decks" (sut/member-of cfg "/ships/cordelia/chart-room"))
      (should-be-nil (sut/member-of cfg "/ships/cordelia/bow"))))

  (it "skips worksites without members"
    (let [cfg {:resource-pools {"dry-dock" {:type :worksite}}}]
      (should= [] (sut/names cfg))))
)
