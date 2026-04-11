(ns net-perspective.peer.batch-test
  "Property tests for pure functions in peer.batch."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [net-perspective.crypto :as crypto]
            [net-perspective.codec :as codec]
            [net-perspective.test-utils]))

(net-perspective.test-utils/deftest-ns-schemas-test)

;; Access private pure functions for direct testing.
(require 'net-perspective.peer.batch)
(def ^:private adjust-hops   @(ns-resolve 'net-perspective.peer.batch 'adjust-hops))
(def ^:private make-crd      @(ns-resolve 'net-perspective.peer.batch 'make-crd))
(def ^:private find-deps-cid @(ns-resolve 'net-perspective.peer.batch 'find-deps-cid))

;; ---------------------------------------------------------------------------
;; Generators

(def gen-cid
  "Generates CID-like alphanumeric strings (20–60 chars)."
  (gen/fmap #(apply str %)
            (gen/vector
             (gen/elements (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
             20 60)))

(def gen-hop
  (gen/fmap (fn [[hop dr-cid archive-cid size]]
              {"crd-hop/hop"             hop
               "crd-hop/dr-addresses"    [dr-cid]
               "crd-hop/archive-address" archive-cid
               "crd-hop/size"            size})
            (gen/tuple (gen/choose 1 10)
                       gen-cid
                       gen-cid
                       (gen/choose 0 100000))))

(def gen-hops (gen/vector gen-hop 0 8))

;; ---------------------------------------------------------------------------
;; adjust-hops properties

(deftest adjust-hops-bounds-test
  (checking "result hops never exceed 10" 50
    [current-hop (gen/choose 1 10)
     hops        gen-hops]
    (is (every? #(<= (get % "crd-hop/hop") 10)
                (adjust-hops current-hop hops)))))

(deftest adjust-hops-count-test
  (checking "result count never exceeds input count" 50
    [current-hop (gen/choose 1 10)
     hops        gen-hops]
    (is (<= (count (adjust-hops current-hop hops))
            (count hops)))))

(deftest adjust-hops-drops-at-10-test
  (checking "hops already at 10 are always dropped when current-hop >= 1" 50
    [hops (gen/vector (gen/fmap #(assoc % "crd-hop/hop" 10) gen-hop) 1 5)]
    (is (empty? (adjust-hops 1 hops)))))

(deftest adjust-hops-increment-test
  (checking "hop-1 input with current-hop 1 yields hop-2 output" 30
    [hop (gen/fmap #(assoc % "crd-hop/hop" 1) gen-hop)]
    (let [result (adjust-hops 1 [hop])]
      (is (= 1 (count result)))
      (is (= 2 (get (first result) "crd-hop/hop"))))))

;; ---------------------------------------------------------------------------
;; make-crd codec round-trip

(deftest make-crd-roundtrip-test
  (checking "make-crd output survives marshal/unmarshal round-trip" 30
    [hops    gen-hops
     src-cid gen-cid
     now     (gen/large-integer* {:min 0})]
    (let [kp    (crypto/generate-key-pair)
          cpath ["food"]
          crd   (make-crd (:user-id kp) now cpath hops [src-cid])
          rt    (codec/unmarshal (codec/marshal crd))]
      (is (= 1           (get rt "crd/version")))
      (is (= (vec cpath) (vec (get rt "crd/context-path"))))
      (is (= (count hops) (count (get rt "crd/hops")))))))

;; ---------------------------------------------------------------------------
;; find-deps-cid properties

(deftest find-deps-cid-empty-test
  (testing "returns nil for empty index"
    (let [kp    (crypto/generate-key-pair)
          index {"crd-idx/version"      1
                 "crd-idx/timestamp-ns" 0
                 "crd-idx/user-id"      (:user-id kp)
                 "crd-idx/contexts"     []}]
      (is (nil? (find-deps-cid index ["food"]))))))

(deftest find-deps-cid-lookup-test
  (checking "finds the right CID; returns nil for absent paths" 30
    [cid gen-cid]
    (let [kp    (crypto/generate-key-pair)
          index {"crd-idx/version"      1
                 "crd-idx/timestamp-ns" 0
                 "crd-idx/user-id"      (:user-id kp)
                 "crd-idx/contexts"
                 [{"crd-idx-ctx/path"        ["food"]
                   "crd-idx-ctx/crd-address" cid
                   "crd-idx-ctx/hops"        1
                   "crd-idx-ctx/size"        0}
                  {"crd-idx-ctx/path"        ["news"]
                   "crd-idx-ctx/crd-address" "othercid1234567890123456789012"
                   "crd-idx-ctx/hops"        1
                   "crd-idx-ctx/size"        0}]}]
      (is (= cid (find-deps-cid index ["food"])))
      (is (nil? (find-deps-cid index ["other"]))))))
