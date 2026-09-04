(ns isaac.worksite.features-runner
  (:require
    [gherclj.main :as gherclj]))

(def DEFAULT-ARGS
  ["-f" "features"
   "-s" "isaac.**-steps"
   "-t" "~slow"
   "-t" "~wip"])

(defn -main [& args]
  (try
    (apply gherclj/-main (concat DEFAULT-ARGS args))
    (finally
      (shutdown-agents))))
