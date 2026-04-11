(ns net-perspective.util
  "Shared byte / hex / base64 utilities."
  (:import [java.util Base64]))

(defn b64-encode
  {:malli/schema [:=> [:cat bytes?] :string]}
  ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn b64-decode
  {:malli/schema [:=> [:cat :string] bytes?]}
  ^bytes [^String s]
  (.decode (Base64/getDecoder) s))

(defn ensure-bytes
  "Returns v as a byte array. Accepts byte arrays (pass-through) or
   base64 strings (decoded). Needed because JSON round-trip converts
   byte arrays to base64 strings."
  {:malli/schema [:=> [:cat [:or bytes? :string]] bytes?]}
  ^bytes [v]
  (cond
    (bytes? v)  v
    (string? v) (b64-decode v)
    :else (throw (ex-info "expected bytes or base64 string" {:value v}))))

(defn bytes->hex
  {:malli/schema [:=> [:cat bytes?] :string]}
  ^String [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xFF)) b)))

(defn ensure-http
  "Ensures s starts with http:// or https://; prepends http:// if missing."
  {:malli/schema [:=> [:cat :string] :string]}
  ^String [^String s]
  (if (.startsWith s "http") s (str "http://" s)))

(defn hex->bytes
  {:malli/schema [:=> [:cat :string] bytes?]}
  ^bytes [^String s]
  (let [len (/ (count s) 2)
        out (byte-array len)]
    (dotimes [i len]
      (aset out i (unchecked-byte (Integer/parseInt (subs s (* i 2) (+ (* i 2) 2)) 16))))
    out))
