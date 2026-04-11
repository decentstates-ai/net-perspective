(ns net-perspective.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [net-perspective.schema :as schema]
            [net-perspective.crypto :as crypto]
            [net-perspective.test-utils])
  (:import [java.util Arrays]))

(net-perspective.test-utils/deftest-ns-schemas-test)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-kp [] (crypto/generate-key-pair))

(defn- sample-dr [kp]
  {"dr/version" 1
   "dr/timestamp-ns"             (System/nanoTime)
   "dr/user-id"                  (:user-id kp)
   "dr/contexts"
   [{"dr-ctx/path" ["food"]
     "dr-ctx/relations"
     [{"dr-rel/type"        "uri"
       "dr-rel-uri/uri"     "https://example.com/recipe"}]}]})

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
      (is (= 1 (get out "dr/version"))))
    (testing "byte array becomes base64 string"
      (is (string? (get out "dr/user-id"))))))

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
      (is (= 1 (get decoded "dr/version")))
      (is (= ["food"]
             (get-in decoded ["dr/contexts" 0
                              "dr-ctx/path"]))))))

(deftest test-unwrap-rejects-wrong-key
  (let [kp1     (make-kp)
        kp2     (make-kp)
        content (sample-dr kp1)
        env     (schema/wrap content kp1)
        ;; splice in kp2's public key — user-id will mismatch
        bad-env (assoc env
                       "env/user-public-key" (:encoded-public-key kp2)
                       "env/user-id"         (:user-id kp2))]
    (testing "signature check fails for wrong key"
      (is (thrown? Exception (schema/unwrap bad-env))))))

(deftest test-unwrap-rejects-tampered-content
  (let [kp      (make-kp)
        content (sample-dr kp)
        env     (schema/wrap content kp)
        tampered (update env "env/content"
                         assoc "dr/version" 99)]
    (testing "signature check fails after content modification"
      (is (thrown? Exception (schema/unwrap tampered))))))

(deftest test-unwrap-rejects-mismatched-user-id
  (let [kp1     (make-kp)
        kp2     (make-kp)
        content (sample-dr kp1)
        env     (schema/wrap content kp1)
        ;; keep kp1's signature but swap in kp2's user-id
        bad-env (assoc env "env/user-id" (:user-id kp2))]
    (testing "user-id mismatch with public key is rejected"
      (is (thrown? Exception (schema/unwrap bad-env))))))

;; ---------------------------------------------------------------------------
;; b64 encode/decode

(deftest test-b64-roundtrip
  (let [bs (byte-array [1 2 3 4 5])]
    (is (Arrays/equals ^bytes bs
                       ^bytes (schema/b64-decode (schema/b64-encode bs))))))
