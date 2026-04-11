(ns net-perspective.peer.registry
  "Peer registry: maps user-id bytes to the peer HTTP base URL that homes them."
  (:require [net-perspective.util :as util]))

(defrecord Registry [state]) ; atom: {b64-id → peer-url}

(defn new-registry
  {:malli/schema [:=> [:cat] :map]}
  []
  (->Registry (atom {})))

(defn register!
  "Associates user-id bytes with a peer base URL."
  {:malli/schema [:=> [:cat :map bytes? :string] :nil]}
  [registry ^bytes user-id peer-url]
  (swap! (:state registry) assoc (util/b64-encode user-id) peer-url))

(defn lookup
  "Returns the peer base URL for user-id bytes, or nil."
  {:malli/schema [:=> [:cat :map bytes?] [:maybe :string]]}
  [registry ^bytes user-id]
  (get @(:state registry) (util/b64-encode user-id)))
