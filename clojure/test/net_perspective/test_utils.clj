(ns net-perspective.test-utils
  "Shared test utilities."
  (:require [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.test :refer [deftest is testing]]
            [malli.instrument :as mi]))

(defmacro deftest-ns-schemas-test
  "Generates a test that runs malli.instrument/check against all m/=>
   function schemas in the namespace corresponding to the current test ns
   (strips the -test suffix). Fails if any schema check fails."
  []
  `(deftest ~'ns-schemas-test
     (testing "function schema checks"
       (let [target-ns# (str/replace (str *ns*) #"-test$" "")
             res# (mi/check
                   {:filters [(fn [n# _s# _d#]
                                (= (str n#) target-ns#))]})]
         (when res# (pprint res#))
         (is (nil? res#))))))
