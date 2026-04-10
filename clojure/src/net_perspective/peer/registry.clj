(ns net-perspective.peer.registry
  "Peer registry: maps user-id bytes to the peer HTTP base URL that homes them."
  (:import [java.util Base64]))

(defn- b64 ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defrecord Registry [state]) ; atom: {b64-id → peer-url}

(defn new-registry []
  (->Registry (atom {})))

(defn register!
  "Associates user-id bytes with a peer base URL."
  [registry ^bytes user-id peer-url]
  (swap! (:state registry) assoc (b64 user-id) peer-url))

(defn lookup
  "Returns the peer base URL for user-id bytes, or nil."
  [registry ^bytes user-id]
  (get @(:state registry) (b64 user-id)))
