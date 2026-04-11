(ns net-perspective.peer.state
  "Atom-backed server state with filesystem cache. All mutations are pure swap! calls."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [net-perspective.schema :as schema]))

(defn new-server
  "Creates a PeerServer map. var-dir is a string path for state cache, or nil
   to skip persistence (useful in tests)."
  ([ipfs-client listen-addr self-kp]
   (new-server ipfs-client listen-addr self-kp nil))
  ([ipfs-client listen-addr self-kp var-dir]
   {:state    (atom {:users {}} :validator #(m/validate schema/ServerState %))
    :ipfs     ipfs-client
    :registry nil
    :self-kp  self-kp
    :addr     listen-addr
    :var-dir  (when var-dir (io/file var-dir))}))
(m/=> new-server
      [:function
       [:=> [:cat :any :string #'schema/KeyPair]         #'schema/PeerServer]
       [:=> [:cat :any :string #'schema/KeyPair [:maybe :string]] #'schema/PeerServer]])

;; ---------------------------------------------------------------------------
;; User map shape:
;;   {:key-pair        kp-map
;;    :ipns-key-name   "user-abc"
;;    :ipns-address    "k51q..."
;;    :latest-dr       nil          ; decoded DirectRelations map (string keys)
;;    :latest-dr-cid   nil
;;    :index-cid       nil}

;; ---------------------------------------------------------------------------
;; Filesystem cache

(defn- user-cache-file [server ^bytes user-id]
  (when-let [vd (:var-dir server)]
    (io/file vd (str (schema/bytes->hex user-id) ".edn"))))

(defn- load-user-cache [server ^bytes user-id]
  (when-let [f (user-cache-file server user-id)]
    (when (.exists ^java.io.File f)
      (edn/read-string (slurp f)))))

(defn- persist-user-cache!
  "Writes the user's mutable state to disk. No-op if var-dir is nil."
  [server ^bytes user-id]
  (when-let [f (user-cache-file server user-id)]
    (let [uid  (schema/b64-encode user-id)
          user (get-in @(:state server) [:users uid])]
      (io/make-parents f)
      (spit f (pr-str {:dr-cid    (or (:latest-dr-cid user) "")
                       :index-cid (or (:index-cid user) "")
                       :latest-dr (:latest-dr user)})))))

;; ---------------------------------------------------------------------------
;; User registration

(defn add-homed-user!
  "Registers a user the peer will home. Restores cached state from disk if
   a cache file exists in var-dir."
  [server user-map]
  (let [uid    (get-in user-map [:key-pair :user-id])
        cached (load-user-cache server uid)
        user-map' (if cached
                    (merge user-map
                           {:latest-dr-cid (not-empty (:dr-cid cached))
                            :index-cid     (not-empty (:index-cid cached))
                            :latest-dr     (:latest-dr cached)})
                    user-map)]
    (swap! (:state server) assoc-in [:users (schema/b64-encode uid)] user-map')))
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
  "Updates a user's latest direct-relations and DR CID, then persists to disk.
   Returns :updated if stored, :stale if the timestamp is not newer."
  [server ^bytes user-id dr-map dr-cid]
  (let [uid    (schema/b64-encode user-id)
        ts-new (get dr-map "dr/timestamp-ns" 0)]
    (if (> ts-new (dr-timestamp server user-id))
      (do (swap! (:state server)
                 #(-> %
                      (assoc-in [:users uid :latest-dr]     dr-map)
                      (assoc-in [:users uid :latest-dr-cid] dr-cid)))
          (persist-user-cache! server user-id)
          :updated)
      :stale)))
(m/=> store-dr!
      [:=> [:cat #'schema/PeerServer #'schema/UserId #'schema/DirectRelations #'schema/Cid]
       [:enum :updated :stale]])

(defn set-index-cid!
  "Records the latest context-relations-deps-index CID for a user, then persists to disk."
  [server ^bytes user-id index-cid]
  (swap! (:state server)
         assoc-in [:users (schema/b64-encode user-id) :index-cid] index-cid)
  (persist-user-cache! server user-id))
(m/=> set-index-cid! [:=> [:cat #'schema/PeerServer #'schema/UserId #'schema/Cid] :any])
