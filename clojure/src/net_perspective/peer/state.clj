(ns net-perspective.peer.state
  "Atom-backed server state. All mutations are pure swap! calls."
  (:require [net-perspective.schema :as schema])
  (:import [java.util Base64]))

(defn- b64 ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

;; ---------------------------------------------------------------------------
;; Server record

(defrecord Server [state    ; atom: {:users {b64-id → user-map}}
                   ipfs     ; ipfs.client/Client
                   registry ; peer.registry/Registry
                   self-kp  ; own key pair map
                   addr])   ; listen address string

(defn new-server [ipfs-client listen-addr self-kp]
  (->Server (atom {:users {}}) ipfs-client nil self-kp listen-addr))

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
  (let [uid (b64 (get-in user-map [:key-pair :user-id]))]
    (swap! (:state server) assoc-in [:users uid] user-map)))

(defn get-user
  "Returns the user-map for user-id bytes, or nil."
  [server ^bytes user-id]
  (get-in @(:state server) [:users (b64 user-id)]))

(defn all-users
  "Returns a snapshot seq of all user-maps."
  [server]
  (vals (get-in @(:state server) [:users])))

(defn store-dr!
  "Updates a user's latest direct-relations and DR CID.
   Returns the updated user-map, or nil if the timestamp is not newer."
  [server ^bytes user-id dr-map dr-cid]
  (let [uid      (b64 user-id)
        ts-new   (get dr-map "direct-relations/timestamp-ns" 0)]
    (let [result (atom nil)]
      (swap! (:state server)
             (fn [s]
               (let [current (get-in s [:users uid])
                     ts-old  (get-in current [:latest-dr "direct-relations/timestamp-ns"] 0)]
                 (if (> ts-new ts-old)
                   (do (reset! result :updated)
                       (-> s
                           (assoc-in [:users uid :latest-dr]     dr-map)
                           (assoc-in [:users uid :latest-dr-cid] dr-cid)))
                   (do (reset! result :stale)
                       s)))))
      @result)))

(defn set-index-cid!
  "Records the latest context-relations-deps-index CID for a user."
  [server ^bytes user-id index-cid]
  (swap! (:state server)
         assoc-in [:users (b64 user-id) :index-cid] index-cid))

(defn dr-timestamp
  "Returns the timestamp-ns of the stored direct-relations, or 0."
  [server ^bytes user-id]
  (get-in @(:state server)
          [:users (b64 user-id) :latest-dr "direct-relations/timestamp-ns"]
          0))
