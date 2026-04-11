(ns net-perspective.peer.state
  "Atom-backed server state. All mutations are pure swap! calls."
  (:require [malli.core :as m]
            [net-perspective.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Server record

(defrecord Server [state    ; atom: {:users {b64-id → user-map}}
                   ipfs     ; ipfs.client/Client
                   registry ; peer.registry/Registry
                   self-kp  ; own key pair map
                   addr])   ; listen address string

(defn new-server
  [ipfs-client listen-addr self-kp]
  (->Server (atom {:users {}}) ipfs-client nil self-kp listen-addr))
(m/=> new-server [:=> [:cat :any :string #'schema/KeyPair] #'schema/PeerServer])

;; ---------------------------------------------------------------------------
;; User map shape:
;;   {:key-pair        kp-map
;;    :ipns-key-name   "user-abc"
;;    :ipns-address    "k51q..."
;;    :latest-dr       nil          ; decoded DirectRelations map (string keys)
;;    :latest-dr-cid   ""
;;    :index-cid       ""}

(defn add-homed-user!
  "Registers a user the peer will home."
  [server user-map]
  (let [uid (schema/b64-encode (get-in user-map [:key-pair :user-id]))]
    (swap! (:state server) assoc-in [:users uid] user-map)))
(m/=> add-homed-user! [:=> [:cat #'schema/PeerServer #'schema/HomedUser] :nil])

(defn get-user
  "Returns the user-map for user-id bytes, or nil."
  [server ^bytes user-id]
  (get-in @(:state server) [:users (schema/b64-encode user-id)]))
(m/=> get-user [:=> [:cat #'schema/PeerServer #'schema/UserId] [:maybe #'schema/HomedUser]])

(defn all-users
  "Returns a snapshot seq of all user-maps."
  [server]
  (vals (get-in @(:state server) [:users])))
(m/=> all-users [:=> [:cat #'schema/PeerServer] [:sequential #'schema/HomedUser]])

(defn dr-timestamp
  "Returns the timestamp-ns of the stored direct-relations, or 0."
  [server ^bytes user-id]
  (get-in @(:state server)
          [:users (schema/b64-encode user-id) :latest-dr "dr/timestamp-ns"]
          0))
(m/=> dr-timestamp [:=> [:cat #'schema/PeerServer #'schema/UserId] :int])

(defn store-dr!
  "Updates a user's latest direct-relations and DR CID.
   Returns :updated if stored, :stale if the timestamp is not newer."
  [server ^bytes user-id dr-map dr-cid]
  (let [uid    (schema/b64-encode user-id)
        ts-new (get dr-map "dr/timestamp-ns" 0)]
    (if (> ts-new (dr-timestamp server user-id))
      (do (swap! (:state server)
                 #(-> %
                      (assoc-in [:users uid :latest-dr]     dr-map)
                      (assoc-in [:users uid :latest-dr-cid] dr-cid)))
          :updated)
      :stale)))
(m/=> store-dr!
      [:=> [:cat #'schema/PeerServer #'schema/UserId #'schema/DirectRelations #'schema/Cid]
       [:enum :updated :stale]])

(defn set-index-cid!
  "Records the latest context-relations-deps-index CID for a user."
  [server ^bytes user-id index-cid]
  (swap! (:state server)
         assoc-in [:users (schema/b64-encode user-id) :index-cid] index-cid))
(m/=> set-index-cid! [:=> [:cat #'schema/PeerServer #'schema/UserId #'schema/Cid] :any])
