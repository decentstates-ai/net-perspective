(ns net-perspective.schema
  "Malli schemas for all net-perspective document types, plus envelope
   wrap/unwrap.

   All documents are RFC 8785 (JCS) canonicalized JSON.
   Byte arrays are base64-encoded (standard, padded) in JSON —
   matching Go's encoding/json behaviour for []byte fields."
  (:require [malli.core :as m]
            [malli.error :as me]
            [cheshire.core :as json]
            [net-perspective.codec :as codec]
            [net-perspective.crypto :as crypto]
            [net-perspective.util :as util]))

;; Aliases kept for call-site compatibility.
(def b64-encode  util/b64-encode)
(def b64-decode  util/b64-decode)
(def ensure-bytes util/ensure-bytes)
(def marshal     codec/marshal)
(def unmarshal   codec/unmarshal)

;; ---------------------------------------------------------------------------
;; Malli schemas
;; Field names are verbatim spec JSON keys (strings, not keywords).
;; We use string keys throughout to match the wire format directly.

(def Envelope
  (m/schema
   [:map {:closed false}
    ["env/content"         :any]
    ["env/user-id"         bytes?]
    ["env/user-public-key" bytes?]
    ["env/signature"       bytes?]]))

(def DirectRelation
  (m/schema
   [:map {:closed false}
    ["dr-rel/type" [:enum "user" "uri"]]
    ["dr-rel-uri/uri"              {:optional true} :string]
    ["dr-rel-uri/name"             {:optional true} :string]
    ["dr-rel-uri/comment"          {:optional true} :string]
    ["dr-rel-user/user-id"         {:optional true} bytes?]
    ["dr-rel-user/context-path"    {:optional true} [:vector :string]]
    ["dr-rel-user/transitive-depth" {:optional true} [:int {:min 1 :max 10}]]
    ["dr-rel-user/subject-glob"    {:optional true} [:int {:min 0 :max 10}]]
    ["dr-rel-user/object-glob"     {:optional true} [:int {:min 0 :max 10}]]]))

(def DirectRelationsContext
  (m/schema
   [:map {:closed false}
    ["dr-ctx/path"      [:vector :string]]
    ["dr-ctx/relations" [:vector DirectRelation]]]))

(def DirectRelations
  (m/schema
   [:map {:closed false}
    ["dr/version"                 :int]
    ["dr/timestamp-ns"            :int]
    ["dr/user-id"                 bytes?]
    ["dr/contexts"                [:vector DirectRelationsContext]]
    ["dr/contact-email"           {:optional true} :string]
    ["dr/contact-signal-username" {:optional true} :string]
    ["dr/contact-number"          {:optional true} :string]]))

(def PeeredUser
  (m/schema
   [:map {:closed false}
    ["user-peered/user-id"         bytes?]
    ["user-peered/crd-idx-address" :string]]))

(def UserInfo
  (m/schema
   [:map {:closed false}
    ["user/version"        :int]
    ["user/timestamp-ns"   :int]
    ["user/user-id"        bytes?]
    ["user/user-public-key" bytes?]
    ["user/dr-address"     {:optional true} :string]
    ["user/trusted-peers"  {:optional true} [:vector bytes?]]
    ["user/peered-users"   {:optional true} [:vector PeeredUser]]]))

(def ContextRelationsDepsHop
  (m/schema
   [:map {:closed false}
    ["crd-hop/hop"             [:int {:min 1 :max 10}]]
    ["crd-hop/dr-addresses"    [:vector bytes?]]
    ["crd-hop/archive-address" bytes?]
    ["crd-hop/size"            :int]]))

(def ContextRelationsDeps
  (m/schema
   [:map {:closed false}
    ["crd/version"          :int]
    ["crd/timestamp-ns"     :int]
    ["crd/user-id"          bytes?]
    ["crd/context-path"     [:vector :string]]
    ["crd/hops"             [:vector ContextRelationsDepsHop]]
    ["crd/source-addresses" {:optional true} [:vector bytes?]]]))

(def ContextRelationsDepsIndexContext
  (m/schema
   [:map {:closed false}
    ["crd-idx-ctx/path"                  [:vector :string]]
    ["crd-idx-ctx/crd-address"           bytes?]
    ["crd-idx-ctx/hops"                  :int]
    ["crd-idx-ctx-hop/archive-addresses" {:optional true} [:vector bytes?]]
    ["crd-idx-ctx/size"                  :int]]))

(def ContextRelationsDepsIndex
  (m/schema
   [:map {:closed false}
    ["crd-idx/version"     :int]
    ["crd-idx/timestamp-ns" :int]
    ["crd-idx/user-id"     bytes?]
    ["crd-idx/contexts"    [:vector ContextRelationsDepsIndexContext]]]))

(def RingResponse
  (m/schema
   [:map
    [:status  :int]
    [:headers [:map-of :string :string]]
    [:body    :any]]))

;; ---------------------------------------------------------------------------
;; Validation

(defn validate!
  "Validates doc against schema. Returns doc on success, throws on failure."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema doc]
  (when-not (m/validate schema doc)
    (throw (ex-info "document validation failed"
                    {:errors (me/humanize (m/explain schema doc))})))
  doc)

;; ---------------------------------------------------------------------------
;; Envelope wrap / unwrap

(defn wrap
  "Signs content-map with kp and returns an envelope map (string keys).
   content-map must be a Clojure map; it is marshalled to JCS bytes for signing.
   kp must have :private-params, :encoded-public-key, and :user-id."
  [content-map kp]
  (let [content-bytes (codec/marshal content-map)
        sig           (crypto/sign (:private-params kp) content-bytes)]
    {"env/content"         (json/parse-string (String. content-bytes "UTF-8"))
     "env/user-id"         (:user-id kp)
     "env/user-public-key" (:encoded-public-key kp)
     "env/signature"       sig}))
(m/=> wrap [:=> [:cat :map :map] #'Envelope])

(defn unwrap
  "Verifies an envelope map and returns the decoded content map.
   Throws if the signature is invalid or user-id is inconsistent.
   Accepts envelopes that have been through a JSON round-trip (byte
   array fields may be base64 strings)."
  [envelope]
  (let [enc-pubkey (util/ensure-bytes (get envelope "env/user-public-key"))
        user-id    (util/ensure-bytes (get envelope "env/user-id"))
        signature  (util/ensure-bytes (get envelope "env/signature"))
        content    (get envelope "env/content")]
    (when-not (java.util.Arrays/equals
               ^bytes user-id
               ^bytes (crypto/compute-user-id enc-pubkey))
      (throw (ex-info "user-id does not match public key" {})))
    (when-not (crypto/verify enc-pubkey (codec/marshal content) signature)
      (throw (ex-info "invalid envelope signature" {})))
    content))
(m/=> unwrap [:=> [:cat #'Envelope] :any])
