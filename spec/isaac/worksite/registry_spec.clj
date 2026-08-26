(ns isaac.worksite.registry-spec
  (:require
    [isaac.worksite.registry :as sut]
    [speclj.core :refer [describe it should-be-nil should=]]))

(describe "worksite registry"

  (it "lists worksites from a config map"
    (let [cfg {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}
                           "galley"     {:members ["/ships/cordelia/galley"]}}}]
      (should= ["chart-room" "galley"] (sut/names cfg))
      (should= {:members ["/ships/cordelia/chart-room"]}
               (sut/lookup cfg "chart-room"))))

  (it "matches a cwd to its worksite member"
    (let [cfg {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}]
      (should= "chart-room" (sut/member-of cfg "/ships/cordelia/chart-room"))
      (should-be-nil (sut/member-of cfg "/ships/cordelia/bow"))))

  (it "skips worksites without members"
    (let [cfg {:worksites {"dry-dock" {}}}]
      (should= [] (sut/names cfg))))
)
