(ns net-perspective.codec
  "JCS (RFC 8785) serialization for net-perspective documents.
   Byte arrays are base64-encoded (standard, padded) to match Go's encoding/json."
  (:require [cheshire.core :as json]
            [net-perspective.util :as util])
  (:import [org.erdtman.jcs JsonCanonicalizer]))

(defn- bytes->b64-map
  "Walk a Clojure data structure, replacing byte arrays with base64 strings."
  [v]
  (cond
    (bytes? v)      (util/b64-encode v)
    (map? v)        (into {} (map (fn [[k val]] [k (bytes->b64-map val)]) v))
    (sequential? v) (mapv bytes->b64-map v)
    :else           v))

(defn marshal
  "Serialise a Clojure map to JCS-canonical JSON bytes.
   Byte arrays are base64-encoded before serialisation."
  {:malli/schema [:=> [:cat :any] bytes?]}
  ^bytes [doc]
  (.getEncodedUTF8 (JsonCanonicalizer. (json/generate-string (bytes->b64-map doc)))))

(defn unmarshal
  "Deserialise JCS JSON bytes to a Clojure map with string keys."
  {:malli/schema [:=> [:cat bytes?] :map]}
  [^bytes data]
  (json/parse-string (String. data "UTF-8")))
