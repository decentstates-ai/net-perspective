(ns net-perspective.schema
  "All net-perspective types, serialisation, and envelope signing in one place.

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
;; Byte / hex / base64 utilities

(def ^:private base64-re
  #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{4})?$")

(defn b64-encode ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))
(m/=> b64-encode [:=> [:cat bytes?] [:re base64-re]])

(defn b64-decode ^bytes [^String s]
  (.decode (Base64/getDecoder) s))
(m/=> b64-decode [:=> [:cat [:re base64-re]] bytes?])

(defn ensure-bytes
  "Returns v as a byte array. Accepts byte arrays (pass-through) or
   base64 strings (decoded). Needed because JSON round-trip converts
   byte arrays to base64 strings."
  ^bytes [v]
  (cond
    (bytes? v)  v
    (string? v) (b64-decode v)
    :else (throw (ex-info "expected bytes or base64 string" {:value v}))))
(m/=> ensure-bytes [:=> [:cat [:or bytes? [:re base64-re]]] bytes?])

(defn bytes->hex ^String [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xFF)) b)))
(m/=> bytes->hex [:=> [:cat bytes?] [:re #"^[0-9a-f]*$"]])

(defn hex->bytes ^bytes [^String s]
  (let [len (/ (count s) 2)
        out (byte-array len)]
    (dotimes [i len]
      (aset out i (unchecked-byte (Integer/parseInt (subs s (* i 2) (+ (* i 2) 2)) 16))))
    out))
(m/=> hex->bytes [:=> [:cat [:re #"^[0-9a-f]+"]] bytes?])

(defn ensure-http
  "Ensures s starts with http:// or https://; prepends http:// if missing."
  ^String [^String s]
  (if (.startsWith s "http") s (str "http://" s)))
(m/=> ensure-http [:=> [:cat :string] [:re #"^https?://"]])

;; ---------------------------------------------------------------------------
;; JCS serialisation

(defn- bytes->b64-map
  "Walk a Clojure data structure, replacing byte arrays with base64 strings."
  [v]
  (cond
    (bytes? v)      (b64-encode v)
    (map? v)        (into {} (map (fn [[k val]] [k (bytes->b64-map val)]) v))
    (sequential? v) (mapv bytes->b64-map v)
    :else           v))

(defn marshal-document
  "Serialise a Clojure map to JCS-canonical JSON bytes.
   Byte arrays are base64-encoded before serialisation."
  ^bytes [document]
  (.getEncodedUTF8 (JsonCanonicalizer. (json/generate-string (bytes->b64-map document)))))
(m/=> marshal-document [:=> [:cat :any] bytes?])

(defn unmarshal-document
  "Deserialise JCS JSON bytes to a Clojure map with string keys."
  [^bytes data]
  (json/parse-string (String. data "UTF-8")))
(m/=> unmarshal-document [:=> [:cat bytes?] :map])

;; ---------------------------------------------------------------------------
;; Primitive type aliases — string types

(def Cid
  "IPFS content identifier string (CIDv0 Qm… or CIDv1 bafy…)."
  [:re #"^[A-Za-z0-9]+$"])

(def HexId
  "Lowercase hex-encoded byte string, e.g. user-id in HTTP endpoints."
  [:re #"^[0-9a-f]+$"])

(def Base64String
  "Standard padded base64-encoded byte string."
  [:re base64-re])

(def PeerUrl
  "HTTP or HTTPS base URL for a remote peer."
  [:re #"^https?://"])

(def IpnsAddress
  "IPNS key address (libp2p CIDv1 key identifier, starts with k51q)."
  [:re #"^k51q"])

(def KeyName
  "Identifier used to register a user's IPNS key with kubo."
  :string)

(def ContextPath
  "Ordered sequence of string segments identifying a context."
  [:vector :string])

;; ---------------------------------------------------------------------------
;; Primitive type aliases — bytes types

(def UserId
  "34-byte SHA2-256 multihash of the encoded public key."
  [:and bytes? [:fn {:error/message "must be 34-byte SHA2-256 multihash"}
                #(= 34 (alength ^bytes %))]])

(def EncodedPublicKey
  "Multicodec-prefixed (varint 0x1203) ML-DSA-44 public key bytes."
  bytes?)

(def Signature
  "ML-DSA-44 (Dilithium2) signature bytes."
  bytes?)

(def Seed
  "32-byte ML-DSA-44 private key seed."
  [:and bytes? [:fn {:error/message "must be 32-byte seed"}
                #(= 32 (alength ^bytes %))]])

(def ContentBytes
  "Raw bytes stored in or retrieved from IPFS."
  bytes?)

;; ---------------------------------------------------------------------------
;; Document schemas
;; Field names are verbatim spec JSON keys (strings, not keywords).
;; We use string keys throughout to match the wire format directly.

(def Envelope
  (m/schema
   [:map {:closed false}
    ["env/content"         :any]
    ["env/user-id"         UserId]
    ["env/user-public-key" EncodedPublicKey]
    ["env/signature"       Signature]]))

(def DirectRelation
  (m/schema
   [:map {:closed false}
    ["dr-rel/type"                    [:enum "user" "uri"]]
    ["dr-rel-uri/uri"                 {:optional true} :string]
    ["dr-rel-uri/name"                {:optional true} :string]
    ["dr-rel-uri/comment"             {:optional true} :string]
    ["dr-rel-user/user-id"            {:optional true} UserId]
    ["dr-rel-user/context-path"       {:optional true} ContextPath]
    ["dr-rel-user/transitive-depth"   {:optional true} [:int {:min 1 :max 10}]]
    ["dr-rel-user/subject-glob"       {:optional true} [:int {:min 0 :max 10}]]
    ["dr-rel-user/object-glob"        {:optional true} [:int {:min 0 :max 10}]]]))

(def DirectRelationsContext
  (m/schema
   [:map {:closed false}
    ["dr-ctx/path"      ContextPath]
    ["dr-ctx/relations" [:vector DirectRelation]]]))

(def DirectRelations
  (m/schema
   [:map {:closed false}
    ["dr/version"                 :int]
    ["dr/timestamp-ns"            :int]
    ["dr/user-id"                 UserId]
    ["dr/contexts"                [:vector DirectRelationsContext]]
    ["dr/contact-email"           {:optional true} :string]
    ["dr/contact-signal-username" {:optional true} :string]
    ["dr/contact-number"          {:optional true} :string]]))

(def PeeredUser
  (m/schema
   [:map {:closed false}
    ["user-peered/user-id"         UserId]
    ["user-peered/crd-idx-address" Cid]]))

(def UserInfo
  (m/schema
   [:map {:closed false}
    ["user/version"         :int]
    ["user/timestamp-ns"    :int]
    ["user/user-id"         UserId]
    ["user/user-public-key" EncodedPublicKey]
    ["user/dr-address"      {:optional true} Cid]
    ["user/trusted-peers"   {:optional true} [:vector EncodedPublicKey]]
    ["user/peered-users"    {:optional true} [:vector PeeredUser]]]))

(def ContextRelationsDepsHop
  (m/schema
   [:map {:closed false}
    ["crd-hop/hop"             [:int {:min 1 :max 10}]]
    ["crd-hop/dr-addresses"    [:vector Cid]]
    ["crd-hop/archive-address" Cid]
    ["crd-hop/size"            :int]]))

(def ContextRelationsDeps
  (m/schema
   [:map {:closed false}
    ["crd/version"          :int]
    ["crd/timestamp-ns"     :int]
    ["crd/user-id"          UserId]
    ["crd/context-path"     ContextPath]
    ["crd/hops"             [:vector ContextRelationsDepsHop]]
    ["crd/source-addresses" {:optional true} [:vector Cid]]]))

(def ContextRelationsDepsIndexContext
  (m/schema
   [:map {:closed false}
    ["crd-idx-ctx/path"                  ContextPath]
    ["crd-idx-ctx/crd-address"           Cid]
    ["crd-idx-ctx/hops"                  :int]
    ["crd-idx-ctx-hop/archive-addresses" {:optional true} [:vector Cid]]
    ["crd-idx-ctx/size"                  :int]]))

(def ContextRelationsDepsIndex
  (m/schema
   [:map {:closed false}
    ["crd-idx/version"      :int]
    ["crd-idx/timestamp-ns" :int]
    ["crd-idx/user-id"      UserId]
    ["crd-idx/contexts"     [:vector ContextRelationsDepsIndexContext]]]))

(def RingResponse
  (m/schema
   [:map
    [:status  :int]
    [:headers [:map-of :string :string]]
    [:body    :any]]))

;; ---------------------------------------------------------------------------
;; Internal peer types

(def KeyPair
  "Key pair map returned by crypto/generate-key-pair or key-pair-from-seed."
  (m/schema
   [:map
    [:private-seed       Seed]
    [:private-params     :any]           ; MLDSAPrivateKeyParameters — opaque BC object
    [:raw-public-key     bytes?]
    [:encoded-public-key EncodedPublicKey]
    [:user-id            UserId]]))

(def HomedUser
  "Internal state map for a user homed on this peer."
  (m/schema
   [:map
    [:key-pair      KeyPair]
    [:ipns-key-name KeyName]
    [:ipns-address  IpnsAddress]
    [:latest-dr     [:maybe :map]]       ; DirectRelations; byte fields may be base64 strings after JSON round-trip
    [:latest-dr-cid [:maybe Cid]]
    [:index-cid     [:maybe Cid]]]))

(def Registry
  "Internal registry record (net-perspective.peer.registry/Registry)."
  (m/schema
   [:map
    [:state :any]]))                     ; atom: {b64-id → peer-url}

(def PeerServer
  "Internal server state record (net-perspective.peer.state/Server)."
  (m/schema
   [:map
    [:state    :any]                     ; atom: {:users {b64-id → HomedUser}}
    [:ipfs     :any]                     ; ipfs.client/Store implementation
    [:registry [:maybe :any]]            ; peer.registry/Registry or nil
    [:self-kp  KeyPair]
    [:addr     :string]
    [:var-dir  [:maybe :any]]]))

;; ---------------------------------------------------------------------------
;; Validation

(defn validate!
  "Validates document against schema. Returns document on success, throws on failure."
  [schema document]
  (when-not (m/validate schema document)
    (throw (ex-info "document validation failed"
                    {:errors (me/humanize (m/explain schema document))})))
  document)
(m/=> validate! [:=> [:cat :any :any] :any])

;; ---------------------------------------------------------------------------
;; Envelope wrap / unwrap

(defn wrap-envelope
  "Signs content-map with kp and returns an envelope map (string keys).
   content-map must be a Clojure map; it is marshalled to JCS bytes for signing.
   kp must have :private-params, :encoded-public-key, and :user-id."
  [content-map kp]
  (let [content-bytes (marshal-document content-map)
        sig           (crypto/sign (:private-params kp) content-bytes)]
    {"env/content"         (json/parse-string (String. content-bytes "UTF-8"))
     "env/user-id"         (:user-id kp)
     "env/user-public-key" (:encoded-public-key kp)
     "env/signature"       sig}))
(m/=> wrap-envelope [:=> [:cat :map #'KeyPair] #'Envelope])

(defn unwrap-envelope
  "Verifies an envelope map and returns the decoded content map.
   Throws if the signature is invalid or user-id is inconsistent.
   Accepts envelopes that have been through a JSON round-trip (byte
   array fields may be base64 strings)."
  [envelope]
  (let [enc-pubkey (ensure-bytes (get envelope "env/user-public-key"))
        user-id    (ensure-bytes (get envelope "env/user-id"))
        signature  (ensure-bytes (get envelope "env/signature"))
        content    (get envelope "env/content")]
    (when-not (java.util.Arrays/equals
               ^bytes user-id
               ^bytes (crypto/compute-user-id enc-pubkey))
      (throw (ex-info "user-id does not match public key" {})))
    (when-not (crypto/verify enc-pubkey (marshal-document content) signature)
      (throw (ex-info "invalid envelope signature" {})))
    content))
(m/=> unwrap-envelope [:=> [:cat #'Envelope] :any])
