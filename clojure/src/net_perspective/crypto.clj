(ns net-perspective.crypto
  "ML-DSA-44 key generation, signing, verification, and multiformat encoding.
   Wire-compatible with the Go implementation's pkg/doc/crypto.go."
  (:import [java.security MessageDigest SecureRandom]
           [org.bouncycastle.pqc.crypto.mldsa
            MLDSAParameters MLDSAKeyGenerationParameters
            MLDSAKeyPairGenerator MLDSASigner
            MLDSAPrivateKeyParameters MLDSAPublicKeyParameters]))

;; ---------------------------------------------------------------------------
;; Varint encoding

(defn- encode-varint
  "Encodes n as an unsigned LEB128 (varint) byte array."
  ^bytes [n]
  (loop [n n acc []]
    (if (< n 128)
      (byte-array (conj acc n))
      (recur (unsigned-bit-shift-right n 7)
             (conj acc (bit-or (bit-and n 0x7F) 0x80))))))

;; multicodec codec ID for ML-DSA-44 public keys: 0x1203
(def ^:private ml-dsa-44-codec-varint (encode-varint 0x1203))

;; ---------------------------------------------------------------------------
;; Public key encoding

(defn encode-public-key
  "Returns multicodec-encoded public key: varint(0x1203) ++ raw-pubkey-bytes."
  ^bytes [^bytes raw-pubkey]
  (let [prefix ml-dsa-44-codec-varint
        out    (byte-array (+ (alength prefix) (alength raw-pubkey)))]
    (System/arraycopy prefix 0 out 0 (alength prefix))
    (System/arraycopy raw-pubkey 0 out (alength prefix) (alength raw-pubkey))
    out))

(defn decode-public-key
  "Strips the multicodec varint prefix and returns the raw public key bytes.
   Throws if the prefix does not match ML-DSA-44 (0x1203)."
  ^bytes [^bytes encoded]
  (let [prefix ml-dsa-44-codec-varint
        plen   (alength prefix)]
    (when-not (java.util.Arrays/equals prefix 0 plen encoded 0 plen)
      (throw (ex-info "unexpected public key codec prefix"
                      {:expected (vec prefix)
                       :got      (vec (take plen encoded))})))
    (let [raw (byte-array (- (alength encoded) plen))]
      (System/arraycopy encoded plen raw 0 (alength raw))
      raw)))

;; ---------------------------------------------------------------------------
;; User ID = SHA2-256 multihash of encoded public key

(def ^:private sha256-code (byte 0x12))
(def ^:private sha256-len  (byte 0x20)) ; 32 bytes

(defn compute-user-id
  "Returns the multihash(SHA2-256) of the encoded public key as a byte array."
  ^bytes [^bytes encoded-pubkey]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") encoded-pubkey)
        out    (byte-array (+ 2 (alength digest)))]
    (aset out 0 sha256-code)
    (aset out 1 sha256-len)
    (System/arraycopy digest 0 out 2 (alength digest))
    out))

;; ---------------------------------------------------------------------------
;; Key pair generation and reconstruction

(defn- make-key-pair-generator []
  (doto (MLDSAKeyPairGenerator.)
    (.init (MLDSAKeyGenerationParameters.
            (SecureRandom.)
            MLDSAParameters/ml_dsa_44))))

(defn generate-key-pair
  "Generates a fresh ML-DSA-44 key pair.
   Returns a map with:
     :private-seed        – 32-byte seed (persisted form)
     :raw-public-key      – 1312-byte raw public key
     :encoded-public-key  – multicodec-prefixed public key
     :user-id             – SHA2-256 multihash of encoded public key"
  []
  (let [kp   (.generateKeyPair (make-key-pair-generator))
        priv ^MLDSAPrivateKeyParameters (.getPrivate kp)
        pub  ^MLDSAPublicKeyParameters  (.getPublic  kp)
        seed (.getSeed priv)
        raw  (.getEncoded pub)
        enc  (encode-public-key raw)]
    {:private-seed       seed
     :private-params     priv
     :raw-public-key     raw
     :encoded-public-key enc
     :user-id            (compute-user-id enc)}))

(defn key-pair-from-seed
  "Reconstructs the full key pair from a 32-byte seed.
   MLDSAPrivateKeyParameters can be built directly from the seed."
  [^bytes seed]
  (let [priv (MLDSAPrivateKeyParameters. MLDSAParameters/ml_dsa_44 seed)
        pub  (.getPublicKeyParameters priv)
        raw  (.getEncoded pub)
        enc  (encode-public-key raw)]
    {:private-seed       seed
     :private-params     priv
     :raw-public-key     raw
     :encoded-public-key enc
     :user-id            (compute-user-id enc)}))

;; ---------------------------------------------------------------------------
;; Sign and verify

(defn sign
  "Signs msg-bytes with the private key. Returns signature bytes.
   Uses update(byte[], 0, len) + generateSignature() — the BC 1.80 API."
  ^bytes [^MLDSAPrivateKeyParameters priv-params ^bytes msg]
  (let [signer (doto (MLDSASigner.)
                 (.init true priv-params)
                 (.update msg 0 (alength msg)))]
    (.generateSignature signer)))

(defn verify
  "Returns true if signature is valid for msg under the encoded public key."
  [^bytes encoded-pubkey ^bytes msg ^bytes signature]
  (let [raw    (decode-public-key encoded-pubkey)
        params (MLDSAPublicKeyParameters. MLDSAParameters/ml_dsa_44 raw)
        signer (doto (MLDSASigner.)
                 (.init false params)
                 (.update msg 0 (alength msg)))]
    (.verifySignature signer signature)))
