(ns net-perspective.peer.batch
  "Inductive batch computation of context-relations-deps for all homed users."
  (:require [clj-http.client :as http]
            [malli.core :as m]
            [net-perspective.schema :as schema]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Pure data constructors

(defn- make-hop1
  "Builds the first-hop entry representing a user's own DR."
  [dr-cid dr-size]
  {"crd-hop/hop"             1
   "crd-hop/dr-addresses"    [dr-cid]
   "crd-hop/archive-address" dr-cid
   "crd-hop/size"            dr-size})
(m/=> make-hop1
      [:=> [:cat #'schema/Cid :int] #'schema/ContextRelationsDepsHop])

(defn- adjust-hops
  "Returns hops with hop counts incremented by current-hop, dropping any that
   would exceed 10."
  [current-hop hops]
  (reduce
   (fn [acc h]
     (let [adjusted (+ current-hop (get h "crd-hop/hop" 1))]
       (if (> adjusted 10)
         acc
         (conj acc (assoc h "crd-hop/hop" adjusted)))))
   []
   hops))
(m/=> adjust-hops
      [:=> [:cat :int [:vector #'schema/ContextRelationsDepsHop]]
       [:vector #'schema/ContextRelationsDepsHop]])

(defn- make-crd
  "Builds a ContextRelationsDeps document from its components."
  [user-id now context-path hops src-cids]
  {"crd/version"          1
   "crd/timestamp-ns"     now
   "crd/user-id"          user-id
   "crd/context-path"     context-path
   "crd/hops"             hops
   "crd/source-addresses" src-cids})
(m/=> make-crd
      [:=> [:cat #'schema/UserId :int #'schema/ContextPath
            [:vector #'schema/ContextRelationsDepsHop]
            [:vector #'schema/Cid]]
       #'schema/ContextRelationsDeps])

(defn- find-deps-cid
  "Returns the crd-address for target-path in a parsed index map, or nil."
  [index target-path]
  (->> (get index "crd-idx/contexts" [])
       (filter #(= (get % "crd-idx-ctx/path") target-path))
       first
       (#(get % "crd-idx-ctx/crd-address"))))
(m/=> find-deps-cid
      [:=> [:cat #'schema/ContextRelationsDepsIndex #'schema/ContextPath]
       [:maybe #'schema/Cid]])

(defn- make-index-ctx-entry
  "Builds one ContextRelationsDepsIndexContext entry from a computed deps document."
  [cpath deps deps-cid]
  {"crd-idx-ctx/path"        cpath
   "crd-idx-ctx/crd-address" deps-cid
   "crd-idx-ctx/hops"        (reduce max 0 (map #(get % "crd-hop/hop" 0) (get deps "crd/hops" [])))
   "crd-idx-ctx/size"        (reduce + 0 (map #(get % "crd-hop/size" 0) (get deps "crd/hops" [])))})
(m/=> make-index-ctx-entry
      [:=> [:cat #'schema/ContextPath #'schema/ContextRelationsDeps #'schema/Cid]
       #'schema/ContextRelationsDepsIndexContext])

(defn- make-index-document
  "Builds a ContextRelationsDepsIndex document."
  [user-id now index-contexts]
  {"crd-idx/version"      1
   "crd-idx/timestamp-ns" now
   "crd-idx/user-id"      user-id
   "crd-idx/contexts"     index-contexts})
(m/=> make-index-document
      [:=> [:cat #'schema/UserId :int [:vector #'schema/ContextRelationsDepsIndexContext]]
       #'schema/ContextRelationsDepsIndex])

(defn- make-user-info-document
  "Builds a UserInfo document."
  [kp now dr-cid]
  {"user/version"         1
   "user/timestamp-ns"    now
   "user/user-id"         (:user-id kp)
   "user/user-public-key" (:encoded-public-key kp)
   "user/dr-address"      dr-cid})
(m/=> make-user-info-document
      [:=> [:cat #'schema/KeyPair :int #'schema/Cid] #'schema/UserInfo])

;; ---------------------------------------------------------------------------
;; IO helpers

(defn- add-document!
  "Marshals document to JCS bytes and adds it to the IPFS store. Returns CID."
  [server document]
  (ipfs/add (:ipfs server) (schema/marshal-document document)))
(m/=> add-document! [:=> [:cat #'schema/PeerServer :any] #'schema/Cid])

;; ---------------------------------------------------------------------------
;; Fetch context deps for a related user

(defn- fetch-user-deps
  "Returns [deps-map deps-cid] for a related user's context, or nil.
   Tries local state first (direct IPFS access), then remote peer via HTTP.
   rel-user-id may be raw bytes or a base64 string (JSON round-trip)."
  [server rel-user-id target-path]
  (let [uid      (schema/ensure-bytes rel-user-id)
        fetch-fn (fn [index-cid fetch-cid]
                   (when-let [ic (not-empty index-cid)]
                     (when-let [dc (find-deps-cid (schema/unmarshal-document (fetch-cid ic)) target-path)]
                       [(schema/unmarshal-document (fetch-cid dc)) dc])))]
    (or
     (when-let [user (state/get-user server uid)]
       (fetch-fn (:index-cid user) #(ipfs/cat (:ipfs server) %)))
     (when-let [peer-url (some-> (:registry server) (registry/lookup uid))]
       (try
         (let [resp (http/get (str peer-url "/status/users/" (schema/bytes->hex uid)) {:as :json})]
           (when (= 200 (:status resp))
             (fetch-fn (get-in resp [:body :index-cid])
                       #(:body (http/get (str peer-url "/cid/" %) {:as :byte-array})))))
         (catch Exception e
           (println (str "batch: remote status fetch failed (" peer-url "): " (.getMessage e)))
           nil))))))
(m/=> fetch-user-deps
      [:=> [:cat #'schema/PeerServer [:or #'schema/UserId #'schema/Base64String] #'schema/ContextPath]
       [:maybe [:tuple #'schema/ContextRelationsDeps #'schema/Cid]]])

;; ---------------------------------------------------------------------------
;; Transitive dep fetching

(defn- fetch-transitive-deps
  "Fetches context-deps from directly-related users and returns
   [additional-hops source-cids]."
  [server dr context-path current-hop]
  (let [contexts (filter #(= (get % "dr-ctx/path") context-path)
                         (get dr "dr/contexts" []))]
    (reduce
     (fn [[hops srcs] rel]
       (if (not= (get rel "dr-rel/type") "user")
         [hops srcs]
         (let [max-depth   (min 10 (max 2 (get rel "dr-rel-user/transitive-depth" 2)))
               rel-user-id (get rel "dr-rel-user/user-id")
               target-path (or (seq (get rel "dr-rel-user/context-path")) context-path)]
           (if (>= current-hop max-depth)
             [hops srcs]
             (if-let [[related-deps deps-cid]
                      (try (fetch-user-deps server rel-user-id target-path)
                           (catch Exception e
                             (println (str "batch: dep fetch failed: " (.getMessage e)))
                             nil))]
               [(into hops (adjust-hops current-hop (get related-deps "crd/hops" [])))
                (if deps-cid (conj srcs deps-cid) srcs)]
               [hops srcs])))))
     [[] []]
     (mapcat #(get % "dr-ctx/relations" []) contexts))))
(m/=> fetch-transitive-deps
      [:=> [:cat #'schema/PeerServer #'schema/DirectRelations #'schema/ContextPath :int]
       [:tuple [:vector #'schema/ContextRelationsDepsHop] [:vector #'schema/Cid]]])

;; ---------------------------------------------------------------------------
;; Compute deps for one user+context

(defn- compute-deps
  [server user context-path now]
  (let [dr      (:latest-dr user)
        dr-cid  (:latest-dr-cid user)
        dr-size (try (alength ^bytes (ipfs/cat (:ipfs server) dr-cid))
                     (catch Exception _ 0))
        hop1    (make-hop1 dr-cid dr-size)
        [extra-hops src-cids] (try (fetch-transitive-deps server dr context-path 1)
                                   (catch Exception e
                                     (println (str "batch: transitive dep fetch failed: " (.getMessage e)))
                                     [[] []]))]
    (make-crd (:user-id (:key-pair user)) now context-path (into [hop1] extra-hops) src-cids)))
(m/=> compute-deps
      [:=> [:cat #'schema/PeerServer #'schema/HomedUser #'schema/ContextPath :int]
       #'schema/ContextRelationsDeps])

;; ---------------------------------------------------------------------------
;; Process one user

(defn- process-user! [server user]
  (let [dr     (:latest-dr user)
        dr-cid (:latest-dr-cid user)
        kp     (:key-pair user)]
    (when (and dr (seq dr-cid))
      (let [now        (System/nanoTime)
            index-ctxs (mapv (fn [ctx]
                               (let [cpath    (get ctx "dr-ctx/path")
                                     deps     (compute-deps server user cpath now)
                                     deps-cid (add-document! server deps)]
                                 (make-index-ctx-entry cpath deps deps-cid)))
                             (get dr "dr/contexts" []))
            index-cid  (add-document! server (make-index-document (:user-id kp) now index-ctxs))]
        (state/set-index-cid! server (:user-id kp) index-cid)
        (let [ui-cid (add-document! server (schema/wrap-envelope (make-user-info-document kp now dr-cid) kp))]
          (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid))))))
(m/=> process-user! [:=> [:cat #'schema/PeerServer #'schema/HomedUser] :nil])

;; ---------------------------------------------------------------------------
;; Public entry point

(defn run-batch!
  "Runs one batch update round for all homed users."
  [server]
  (doseq [user (state/all-users server)]
    (try (process-user! server user)
         (catch Exception e
           (println (str "batch: user error: " (.getMessage e)))))))
(m/=> run-batch! [:=> [:cat #'schema/PeerServer] :nil])
