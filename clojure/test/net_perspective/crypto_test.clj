(ns net-perspective.crypto-test
  (:require [clojure.test :refer [deftest is testing]]
            [net-perspective.crypto :as crypto])
  (:import [java.util Arrays]))

(deftest test-generate-key-pair
  (let [kp (crypto/generate-key-pair)]
    (testing "returns required keys"
      (is (bytes? (:private-seed kp)))
      (is (bytes? (:encoded-public-key kp)))
      (is (bytes? (:user-id kp))))
    (testing "seed is 32 bytes"
      (is (= 32 (alength ^bytes (:private-seed kp)))))
    (testing "encoded public key has ml-dsa-44 varint prefix [0x83 0x24]"
      (let [enc ^bytes (:encoded-public-key kp)]
        (is (= (unchecked-byte 0x83) (aget enc 0)))
        (is (= (unchecked-byte 0x24) (aget enc 1)))))
    (testing "user-id has SHA2-256 multihash prefix [0x12 0x20]"
      (let [uid ^bytes (:user-id kp)]
        (is (= (unchecked-byte 0x12) (aget uid 0)))
        (is (= (unchecked-byte 0x20) (aget uid 1)))
        (is (= 34 (alength uid)))))))

(deftest test-key-pair-from-seed-roundtrip
  (let [kp1 (crypto/generate-key-pair)
        kp2 (crypto/key-pair-from-seed (:private-seed kp1))]
    (testing "reconstructed encoded public key matches"
      (is (Arrays/equals ^bytes (:encoded-public-key kp1)
                         ^bytes (:encoded-public-key kp2))))
    (testing "reconstructed user-id matches"
      (is (Arrays/equals ^bytes (:user-id kp1)
                         ^bytes (:user-id kp2))))))

(deftest test-sign-and-verify
  (let [kp  (crypto/generate-key-pair)
        msg (.getBytes "hello net-perspective")]
    (testing "valid signature verifies"
      (let [sig (crypto/sign (:private-params kp) msg)]
        (is (crypto/verify (:encoded-public-key kp) msg sig))))
    (testing "tampered message fails verification"
      (let [sig      (crypto/sign (:private-params kp) msg)
            tampered (.getBytes "hello net-perspectivX")]
        (is (not (crypto/verify (:encoded-public-key kp) tampered sig)))))
    (testing "wrong key fails verification"
      (let [other-kp (crypto/generate-key-pair)
            sig      (crypto/sign (:private-params kp) msg)]
        (is (not (crypto/verify (:encoded-public-key other-kp) msg sig)))))))

(deftest test-decode-public-key
  (let [kp  (crypto/generate-key-pair)
        enc (:encoded-public-key kp)
        raw (crypto/decode-public-key enc)]
    (testing "decodes to 1312-byte raw key"
      (is (= 1312 (alength ^bytes raw))))
    (testing "re-encoding matches original"
      (is (Arrays/equals ^bytes enc ^bytes (crypto/encode-public-key raw))))))
