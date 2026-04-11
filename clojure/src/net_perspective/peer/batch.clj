(ns net-perspective.peer.batch
  "Inductive batch computation of context-relations-deps for all homed users."
  (:require [clj-http.client :as http]
            [malli.core :as m]
            [net-perspective.codec :as codec]
            [net-perspective.schema :as schema]
            [net-perspective.util :as util]
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
  "Builds one ContextRelationsDepsIndexContext entry from a computed deps doc."
  [cpath deps deps-cid]
  {"crd-idx-ctx/path"        cpath
   "crd-idx-ctx/crd-address" deps-cid
   "crd-idx-ctx/hops"        (reduce max 0 (map #(get % "crd-hop/hop" 0) (get deps "crd/hops" [])))
   "crd-idx-ctx/size"        (reduce + 0 (map #(get % "crd-hop/size" 0) (get deps "crd/hops" [])))})
(m/=> make-index-ctx-entry
      [:=> [:cat #'schema/ContextPath #'schema/ContextRelationsDeps #'schema/Cid]
       #'schema/ContextRelationsDepsIndexContext])

(defn- make-index-doc
  "Builds a ContextRelationsDepsIndex document."
  [user-id now index-contexts]
  {"crd-idx/version"      1
   "crd-idx/timestamp-ns" now
   "crd-idx/user-id"      user-id
   "crd-idx/contexts"     index-contexts})
(m/=> make-index-doc
      [:=> [:cat #'schema/UserId :int [:vector #'schema/ContextRelationsDepsIndexContext]]
       #'schema/ContextRelationsDepsIndex])

(defn- make-user-info-doc
  "Builds a UserInfo document."
  [kp now dr-cid]
  {"user/version"         1
   "user/timestamp-ns"    now
   "user/user-id"         (:user-id kp)
   "user/user-public-key" (:encoded-public-key kp)
   "user/dr-address"      dr-cid})
(m/=> make-user-info-doc
      [:=> [:cat #'schema/KeyPair :int #'schema/Cid] #'schema/UserInfo])

;; ---------------------------------------------------------------------------
;; IO helpers

(defn- add-doc!
  "Marshals doc to JCS bytes and adds it to the IPFS store. Returns CID."
  {:malli/schema [:=> [:cat #'schema/PeerServer :any] #'schema/Cid]}
  [server doc]
  (ipfs/add (:ipfs server) (codec/marshal doc)))

(defn- fetch-bytes
  "Fetches raw bytes for cid from IPFS."
  {:malli/schema [:=> [:cat #'schema/PeerServer #'schema/Cid] #'schema/ContentBytes]}
  [server cid]
  (ipfs/cat (:ipfs server) cid))

;; ---------------------------------------------------------------------------
;; Fetch context deps for a related user

(defn- fetch-deps-from-index
  "Given serialised index bytes and a target context path, fetches and returns
   [deps-map deps-cid] using fetch-fn (cid → bytes)."
  [index-bytes target-path fetch-fn]
  (let [deps-cid (find-deps-cid (codec/unmarshal index-bytes) target-path)]
    (when deps-cid
      [(codec/unmarshal (fetch-fn deps-cid)) deps-cid])))
(m/=> fetch-deps-from-index
      [:=> [:cat #'schema/ContentBytes #'schema/ContextPath fn?]
       [:maybe [:tuple #'schema/ContextRelationsDeps #'schema/Cid]]])

(defn- fetch-local-user-context-deps
  "Fetches context deps for a locally-homed user."
  [server related-user target-path]
  (when-let [index-cid (not-empty (:index-cid related-user))]
    (fetch-deps-from-index (fetch-bytes server index-cid) target-path
                           #(fetch-bytes server %))))
(m/=> fetch-local-user-context-deps
      [:=> [:cat #'schema/PeerServer #'schema/HomedUser #'schema/ContextPath]
       [:maybe [:tuple #'schema/ContextRelationsDeps #'schema/Cid]]])

(defn- fetch-remote-user-context-deps
  "Fetches context deps for a user homed on a remote peer via HTTP."
  [peer-url ^bytes user-id target-path]
  (let [hex-id  (util/bytes->hex user-id)
        st-resp (try (http/get (str peer-url "/status/users/" hex-id) {:as :json})
                     (catch Exception e
                       (println (str "batch: remote status fetch failed (" peer-url "): " (.getMessage e)))
                       nil))]
    (when (and st-resp (= 200 (:status st-resp)))
      ;; clj-http keywordizes JSON response keys by default.
      (let [index-cid (get-in st-resp [:body :index-cid])]
        (when (seq index-cid)
          (let [fetch-fn #(:body (http/get (str peer-url "/cid/" %) {:as :byte-array}))]
            (fetch-deps-from-index (fetch-fn index-cid) target-path fetch-fn)))))))
(m/=> fetch-remote-user-context-deps
      [:=> [:cat #'schema/PeerUrl #'schema/UserId #'schema/ContextPath]
       [:maybe [:tuple #'schema/ContextRelationsDeps #'schema/Cid]]])

(defn- fetch-user-context-deps
  "Resolves a related user's context-deps, trying local first then remote.
   rel-user-id may be raw bytes or a base64 string (JSON round-trip)."
  [server rel-user-id target-path]
  (let [uid        (util/ensure-bytes rel-user-id)
        local-user (state/get-user server uid)]
    (or (when local-user
          (fetch-local-user-context-deps server local-user target-path))
        (when-let [peer-url (some-> (:registry server)
                                    (registry/lookup uid))]
          (fetch-remote-user-context-deps peer-url uid target-path)))))
(m/=> fetch-user-context-deps
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
                      (try (fetch-user-context-deps server rel-user-id target-path)
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
        dr-size (try (alength ^bytes (fetch-bytes server dr-cid))
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

(defn- process-user!
  {:malli/schema [:=> [:cat #'schema/PeerServer #'schema/HomedUser] :nil]}
  [server user]
  (let [dr     (:latest-dr user)
        dr-cid (:latest-dr-cid user)
        kp     (:key-pair user)]
    (when (and dr (seq dr-cid))
      (let [now        (System/nanoTime)
            index-ctxs (mapv (fn [ctx]
                               (let [cpath    (get ctx "dr-ctx/path")
                                     deps     (compute-deps server user cpath now)
                                     deps-cid (add-doc! server deps)]
                                 (make-index-ctx-entry cpath deps deps-cid)))
                             (get dr "dr/contexts" []))
            index-cid  (add-doc! server (make-index-doc (:user-id kp) now index-ctxs))]
        (state/set-index-cid! server (:user-id kp) index-cid)
        (let [ui-cid (add-doc! server (schema/wrap (make-user-info-doc kp now dr-cid) kp))]
          (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid))))))

;; ---------------------------------------------------------------------------
;; Public entry point

(defn run-batch!
  "Runs one batch update round for all homed users."
  {:malli/schema [:=> [:cat #'schema/PeerServer] :nil]}
  [server]
  (doseq [user (state/all-users server)]
    (try (process-user! server user)
         (catch Exception e
           (println (str "batch: user error: " (.getMessage e)))))))
