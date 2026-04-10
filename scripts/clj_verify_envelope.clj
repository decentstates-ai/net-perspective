;; Reads a cross-compat fixture JSON from stdin,
;; calls schema/unwrap on the envelope, exits 0 on success.
(require '[cheshire.core :as json]
         '[net-perspective.schema :as schema])

(let [fix     (json/parse-string (slurp *in*))
      env     (get fix "envelope")
      content (schema/unwrap env)]
  (println "ok: Clojure verified Go envelope — content keys:" (keys content)))
