;; Generates a key pair + DR envelope, prints fixture JSON to stdout.
;; Output: {"encoded_public_key": "<b64>", "envelope": {...}}
(require '[cheshire.core :as json]
         '[net-perspective.crypto :as crypto]
         '[net-perspective.schema :as schema])

(let [kp  (crypto/generate-key-pair)
      dr  {"dr/version" 1
           "dr/timestamp-ns"             (System/nanoTime)
           "dr/user-id"                  (:user-id kp)
           "dr/contexts"
           [{"dr-ctx/path" ["cross-compat"]
             "dr-ctx/relations"
             [{"dr-rel/type"    "uri"
               "dr-rel-uri/uri" "https://cross-compat.example.com"}]}]}
      env (schema/wrap dr kp)]
  (println (json/generate-string
            {"encoded_public_key" (schema/b64-encode (:encoded-public-key kp))
             "envelope"           env})))
