(ns net-perspective.test-runner
  (:require [cognitect.test-runner.api :as tr]))

(defn -main [& args]
  (tr/test {:dirs ["test"]}))
