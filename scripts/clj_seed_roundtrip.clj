;; Reads a base64 seed from stdin, reconstructs the key pair,
;; and prints the base64-encoded public key — used to verify seed compatibility.
(require '[net-perspective.crypto :as crypto]
         '[net-perspective.schema :as schema])

(let [seed    (schema/b64-decode (clojure.string/trim (slurp *in*)))
      kp      (crypto/key-pair-from-seed seed)]
  (println (schema/b64-encode (:encoded-public-key kp))))
