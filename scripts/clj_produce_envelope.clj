;; Generates a key pair + DR envelope, prints fixture JSON to stdout.
;; Output: {"encoded_public_key": "<b64>", "envelope": {...}}
(require '[cheshire.core :as json]
         '[net-perspective.crypto :as crypto]
         '[net-perspective.schema :as schema])

(let [kp  (crypto/generate-key-pair)
      dr  {"direct-relations/direct-relations-version" 1
           "direct-relations/timestamp-ns"             (System/nanoTime)
           "direct-relations/user-id"                  (:user-id kp)
           "direct-relations/contexts"
           [{"direct-relations-context/context-path" ["cross-compat"]
             "direct-relations-context/relations"
             [{"direct-relations-rel/type"    "uri"
               "direct-relations-rel-uri/uri" "https://cross-compat.example.com"}]}]}
      env (schema/wrap dr kp)]
  (println (json/generate-string
            {"encoded_public_key" (schema/b64-encode (:encoded-public-key kp))
             "envelope"           env})))
