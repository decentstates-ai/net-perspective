(ns net-perspective.schema
  "Malli schemas for all net-perspective document types, plus JCS
   marshal/unmarshal and envelope wrap/unwrap.

   All documents are RFC 8785 (JCS) canonicalized JSON.
   Byte arrays are base64-encoded (standard, padded) in JSON —
   matching Go's encoding/json behaviour for []byte fields."
  (:require [malli.core :as m]
            [malli.error :as me]
            [cheshire.core :as json]
            [net-perspective.crypto :as crypto])
  (:import [java.util Base64]
           [org.erdtman.jcs JsonCanonicalizer]))

;; ---------------------------------------------------------------------------
;; Base64 helpers (standard, padded — matches Go encoding/json []byte)

(defn b64-encode ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn b64-decode ^bytes [^String s]
  (.decode (Base64/getDecoder) s))

;; ---------------------------------------------------------------------------
;; JSON / JCS

(defn- bytes->b64-map
  "Walk a Clojure data structure, replacing byte arrays with base64 strings."
  [v]
  (cond
    (bytes? v)       (b64-encode v)
    (map? v)         (into {} (map (fn [[k val]] [k (bytes->b64-map val)]) v))
    (sequential? v)  (mapv bytes->b64-map v)
    :else            v))

(defn marshal
  "Serialise a Clojure map to JCS-canonical JSON bytes.
   Byte arrays are base64-encoded before serialisation."
  ^bytes [doc]
  (.getEncodedUTF8 (JsonCanonicalizer. (json/generate-string (bytes->b64-map doc)))))

(defn unmarshal
  "Deserialise JCS JSON bytes to a Clojure map with string keys."
  [^bytes data]
  (json/parse-string (String. data "UTF-8")))

;; ---------------------------------------------------------------------------
;; Malli schemas
;; Field names are verbatim spec JSON keys (strings, not keywords).
;; We use string keys throughout to match the wire format directly.

(def Envelope
  (m/schema
   [:map {:closed false}
    ["envelope/content"        :any]
    ["envelope/user-id"        bytes?]
    ["envelope/user-public-key" bytes?]
    ["envelope/signature"      bytes?]]))

(def DirectRelation
  (m/schema
   [:map {:closed false}
    ["direct-relations-rel/type" [:enum "user" "uri"]]
    ["direct-relations-rel-uri/uri"     {:optional true} :string]
    ["direct-relations-rel-uri/name"    {:optional true} :string]
    ["direct-relations-rel-uri/comment" {:optional true} :string]
    ["direct-relations-rel-user/user-id"         {:optional true} bytes?]
    ["direct-relations-rel-user/context-path"    {:optional true} [:vector :string]]
    ["direct-relations-rel-user/transitive-depth" {:optional true} [:int {:min 1 :max 10}]]
    ["direct-relations-rel-user/subject-glob"    {:optional true} [:int {:min 0 :max 10}]]
    ["direct-relations-rel-user/object-glob"     {:optional true} [:int {:min 0 :max 10}]]]))

(def DirectRelationsContext
  (m/schema
   [:map {:closed false}
    ["direct-relations-context/context-path" [:vector :string]]
    ["direct-relations-context/relations"    [:vector DirectRelation]]]))

(def DirectRelations
  (m/schema
   [:map {:closed false}
    ["direct-relations/direct-relations-version" :int]
    ["direct-relations/timestamp-ns"             :int]
    ["direct-relations/user-id"                  bytes?]
    ["direct-relations/contexts"                 [:vector DirectRelationsContext]]
    ["direct-relations/contact-email"            {:optional true} :string]
    ["direct-relations/contact-signal-username"  {:optional true} :string]
    ["direct-relations/contact-number"           {:optional true} :string]]))

(def PeeredUser
  (m/schema
   [:map {:closed false}
    ["user-info-peered-user/user-id"                          bytes?]
    ["user-info-peered-user/context-relations-deps-index-address" :string]]))

(def UserInfo
  (m/schema
   [:map {:closed false}
    ["user-info/version"                          :int]
    ["user-info/timestamp-ns"                     :int]
    ["user-info/user-id"                          bytes?]
    ["user-info/user-public-key"                  bytes?]
    ["user-info/direct-relations-content-address" {:optional true} :string]
    ["user-info/trusted-peers"                    {:optional true} [:vector bytes?]]
    ["user-info/peered-users"                     {:optional true} [:vector PeeredUser]]]))

(def ContextRelationsDepsHop
  (m/schema
   [:map {:closed false}
    ["context-relations-deps-hop/hop"                          [:int {:min 1 :max 10}]]
    ["context-relations-deps-hop/direct-relations-addresses"   [:vector bytes?]]
    ["context-relations-deps-hop/direct-relations-archive-address" bytes?]
    ["context-relations-deps-hop/size"                         :int]]))

(def ContextRelationsDeps
  (m/schema
   [:map {:closed false}
    ["context-relations-deps/version"      :int]
    ["context-relations-deps/timestamp-ns" :int]
    ["context-relations-deps/user-id"      bytes?]
    ["context-relations-deps/context-path" [:vector :string]]
    ["context-relations-deps/hops"         [:vector ContextRelationsDepsHop]]
    ["context-relations-deps/source-context-relations-deps-content-addresses"
     {:optional true} [:vector bytes?]]]))

(def ContextRelationsDepsIndexContext
  (m/schema
   [:map {:closed false}
    ["context-relations-deps-index-context/context-path"
     [:vector :string]]
    ["context-relations-deps-index-context/context-relations-deps-content-address"
     bytes?]
    ["context-relations-deps-index-context/hops" :int]
    ["context-relations-deps-index-context-hop/direct-relations-collection-context-address"
     {:optional true} [:vector bytes?]]
    ["context-relations-deps-index-context/size" :int]]))

(def ContextRelationsDepsIndex
  (m/schema
   [:map {:closed false}
    ["context-relations-deps-index/version"      :int]
    ["context-relations-deps-index/timestamp-ns" :int]
    ["context-relations-deps-index/user-id"      bytes?]
    ["context-relations-deps-index/contexts"
     [:vector ContextRelationsDepsIndexContext]]]))

;; ---------------------------------------------------------------------------
;; Validation helpers

(defn validate!
  "Validates doc against schema. Returns doc on success, throws on failure."
  [schema doc]
  (when-not (m/validate schema doc)
    (throw (ex-info "document validation failed"
                    {:errors (me/humanize (m/explain schema doc))})))
  doc)

;; ---------------------------------------------------------------------------
;; Envelope wrap / unwrap
;;
;; The envelope format uses string keys throughout — content is stored as
;; a raw JSON string, not re-encoded, so the signature covers the canonical
;; JSON bytes of the inner document.

(defn wrap
  "Signs content-map with kp and returns an envelope map (string keys).
   content-map must be a Clojure map; it is marshalled to JCS bytes for signing.
   kp must have :private-params and :encoded-public-key and :user-id."
  [content-map kp]
  (let [content-bytes (marshal content-map)
        sig           (crypto/sign (:private-params kp) content-bytes)]
    {"envelope/content"         (json/parse-string (String. content-bytes "UTF-8"))
     "envelope/user-id"         (:user-id kp)
     "envelope/user-public-key" (:encoded-public-key kp)
     "envelope/signature"       sig}))

(defn ensure-bytes
  "Returns v as a byte array. Accepts byte arrays (pass-through) or
   base64 strings (decoded). Needed because JSON round-trip converts
   byte arrays to base64 strings."
  ^bytes [v]
  (cond
    (bytes? v)  v
    (string? v) (b64-decode v)
    :else (throw (ex-info "expected bytes or base64 string" {:value v}))))

(defn unwrap
  "Verifies an envelope map and returns the decoded content map.
   Throws if the signature is invalid or user-id is inconsistent.
   Accepts envelopes that have been through a JSON round-trip (byte
   array fields may be base64 strings)."
  [envelope]
  (let [enc-pubkey (ensure-bytes (get envelope "envelope/user-public-key"))
        user-id    (ensure-bytes (get envelope "envelope/user-id"))
        signature  (ensure-bytes (get envelope "envelope/signature"))
        content    (get envelope "envelope/content")]
    (when-not (java.util.Arrays/equals
               ^bytes user-id
               ^bytes (crypto/compute-user-id enc-pubkey))
      (throw (ex-info "user-id does not match public key" {})))
    (let [content-bytes (marshal content)]
      (when-not (crypto/verify enc-pubkey content-bytes signature)
        (throw (ex-info "invalid envelope signature" {}))))
    content))
