;; Generates a fresh key pair and prints the base64-encoded 32-byte seed to stdout.
(require '[net-perspective.crypto :as crypto]
         '[net-perspective.schema :as schema])

(let [kp (crypto/generate-key-pair)]
  (println (schema/b64-encode (:private-seed kp))))
