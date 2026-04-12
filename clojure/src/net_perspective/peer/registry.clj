(ns net-perspective.peer.registry
  "Peer registry: maps user-id bytes to their latest CRD index CID."
  (:require [malli.core :as m]
            [net-perspective.schema :as schema]))

(defrecord Registry [state]) ; atom: {b64-id → index-cid}

(defn new-registry [] (->Registry (atom {})))
(m/=> new-registry [:=> [:cat] #'schema/Registry])

(defn register!
  "Associates user-id bytes with a CRD index CID."
  [registry ^bytes user-id index-cid]
  (swap! (:state registry) assoc (schema/b64-encode user-id) index-cid))
(m/=> register! [:=> [:cat #'schema/Registry #'schema/UserId #'schema/Cid] :nil])

(defn lookup
  "Returns the CRD index CID for user-id bytes, or nil."
  [registry ^bytes user-id]
  (get @(:state registry) (schema/b64-encode user-id)))
(m/=> lookup [:=> [:cat #'schema/Registry #'schema/UserId] [:maybe #'schema/Cid]])
