(ns net-perspective.peer.registry
  "Peer registry: maps user-id bytes to the peer HTTP base URL that homes them."
  (:require [malli.core :as m]
            [net-perspective.schema :as schema]))

(defrecord Registry [state]) ; atom: {b64-id → peer-url}

(defn new-registry [] (->Registry (atom {})))
(m/=> new-registry [:=> [:cat] #'schema/Registry])

(defn register!
  "Associates user-id bytes with a peer base URL."
  [registry ^bytes user-id peer-url]
  (swap! (:state registry) assoc (schema/b64-encode user-id) peer-url))
(m/=> register! [:=> [:cat #'schema/Registry #'schema/UserId #'schema/PeerUrl] :nil])

(defn lookup
  "Returns the peer base URL for user-id bytes, or nil."
  [registry ^bytes user-id]
  (get @(:state registry) (schema/b64-encode user-id)))
(m/=> lookup [:=> [:cat #'schema/Registry #'schema/UserId] [:maybe #'schema/PeerUrl]])
