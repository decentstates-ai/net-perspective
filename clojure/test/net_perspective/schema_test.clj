(ns net-perspective.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [net-perspective.schema :as schema]
            [net-perspective.crypto :as crypto])
  (:import [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-kp [] (crypto/generate-key-pair))

(defn- sample-dr [kp]
  {"direct-relations/direct-relations-version" 1
   "direct-relations/timestamp-ns"             (System/nanoTime)
   "direct-relations/user-id"                  (:user-id kp)
   "direct-relations/contexts"
   [{"direct-relations-context/context-path" ["food"]
     "direct-relations-context/relations"
     [{"direct-relations-rel/type"        "uri"
       "direct-relations-rel-uri/uri"     "https://example.com/recipe"}]}]})

;; ---------------------------------------------------------------------------
;; Marshal / unmarshal

(deftest test-marshal-roundtrip
  (let [kp  (make-kp)
        doc (sample-dr kp)
        bs  (schema/marshal doc)
        out (schema/unmarshal bs)]
    (testing "unmarshal returns a map"
      (is (map? out)))
    (testing "version survives roundtrip"
      (is (= 1 (get out "direct-relations/direct-relations-version"))))
    (testing "byte array becomes base64 string"
      (is (string? (get out "direct-relations/user-id"))))))

(deftest test-marshal-is-canonical
  (let [kp  (make-kp)
        doc {"b" 2 "a" 1 "z" 0}
        b1  (schema/marshal doc)
        b2  (schema/marshal {"z" 0 "b" 2 "a" 1})]
    (testing "key order does not affect output"
      (is (Arrays/equals ^bytes b1 ^bytes b2)))))

;; ---------------------------------------------------------------------------
;; Wrap / unwrap

(deftest test-wrap-unwrap-roundtrip
  (let [kp      (make-kp)
        content (sample-dr kp)
        env     (schema/wrap content kp)
        decoded (schema/unwrap env)]
    (testing "unwrapped content matches original fields"
      (is (= 1 (get decoded "direct-relations/direct-relations-version")))
      (is (= ["food"]
             (get-in decoded ["direct-relations/contexts" 0
                              "direct-relations-context/context-path"]))))))

(deftest test-unwrap-rejects-wrong-key
  (let [kp1     (make-kp)
        kp2     (make-kp)
        content (sample-dr kp1)
        env     (schema/wrap content kp1)
        ;; splice in kp2's public key — user-id will mismatch
        bad-env (assoc env
                       "envelope/user-public-key" (:encoded-public-key kp2)
                       "envelope/user-id"         (:user-id kp2))]
    (testing "signature check fails for wrong key"
      (is (thrown? Exception (schema/unwrap bad-env))))))

(deftest test-unwrap-rejects-tampered-content
  (let [kp      (make-kp)
        content (sample-dr kp)
        env     (schema/wrap content kp)
        tampered (update env "envelope/content"
                         assoc "direct-relations/direct-relations-version" 99)]
    (testing "signature check fails after content modification"
      (is (thrown? Exception (schema/unwrap tampered))))))

(deftest test-unwrap-rejects-mismatched-user-id
  (let [kp1     (make-kp)
        kp2     (make-kp)
        content (sample-dr kp1)
        env     (schema/wrap content kp1)
        ;; keep kp1's signature but swap in kp2's user-id
        bad-env (assoc env "envelope/user-id" (:user-id kp2))]
    (testing "user-id mismatch with public key is rejected"
      (is (thrown? Exception (schema/unwrap bad-env))))))

;; ---------------------------------------------------------------------------
;; b64 encode/decode

(deftest test-b64-roundtrip
  (let [bs (byte-array [1 2 3 4 5])]
    (is (Arrays/equals ^bytes bs
                       ^bytes (schema/b64-decode (schema/b64-encode bs))))))
