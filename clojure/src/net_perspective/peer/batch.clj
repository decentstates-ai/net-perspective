(ns net-perspective.peer.batch
  "Inductive batch computation of context-relations-deps for all homed users."
  (:require [clj-http.client :as http]
            [malli.core :as m]
            [net-perspective.schema :as schema]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipOutputStream ZipEntry]))

;; ---------------------------------------------------------------------------
;; Pure helpers (adjust-hops, make-crd, find-deps-cid are tested directly)

(defn- adjust-hops
  "Increments hop counts by current-hop, dropping any that would exceed 10."
  [current-hop hops]
  (into []
        (comp (map #(update % "crd-hop/hop" + current-hop))
              (filter #(<= (get % "crd-hop/hop") 10)))
        hops))

(defn- make-crd
  "Builds a ContextRelationsDeps document."
  [user-id now context-path hops src-cids]
  {"crd/version"          1
   "crd/timestamp-ns"     now
   "crd/user-id"          user-id
   "crd/context-path"     context-path
   "crd/hops"             hops
   "crd/source-addresses" src-cids})

(defn- find-deps-cid
  "Returns the crd-address for target-path in a parsed index, or nil."
  [index target-path]
  (some #(when (= (get % "crd-idx-ctx/path") target-path)
           (get % "crd-idx-ctx/crd-address"))
        (get index "crd-idx/contexts" [])))

;; ---------------------------------------------------------------------------
;; IO helpers

(defn- add-document! [server document]
  (ipfs/add (:ipfs server) (schema/marshal-document document)))

(defn- zip-archive ^bytes [entries]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [zos (ZipOutputStream. baos)]
      (doseq [[name ^bytes data] entries]
        (.putNextEntry zos (ZipEntry. ^String name))
        (.write zos data)
        (.closeEntry zos)))
    (.toByteArray baos)))

;; ---------------------------------------------------------------------------
;; Fetch context deps for a related user

(defn- fetch-deps-via-index
  "Given a fetch-bytes fn and an index CID, resolves deps for target-path.
   Returns [deps-map deps-cid] or nil."
  [fetch-bytes index-cid target-path]
  (when-let [ic (not-empty index-cid)]
    (when-let [dc (find-deps-cid (schema/unmarshal-document (fetch-bytes ic)) target-path)]
      [(schema/unmarshal-document (fetch-bytes dc)) dc])))

(defn- fetch-user-deps
  "Returns [deps-map deps-cid] for a related user's context, or nil.
   Tries local state first, then remote peer via HTTP."
  [server rel-user-id target-path]
  (let [uid (schema/ensure-bytes rel-user-id)]
    (or
     (when-let [user (state/get-user server uid)]
       (fetch-deps-via-index #(ipfs/cat (:ipfs server) %) (:index-cid user) target-path))
     (when-let [peer-url (some-> (:registry server) (registry/lookup uid))]
       (try
         (let [resp (http/get (str peer-url "/status/users/" (schema/bytes->hex uid)) {:as :json})]
           (when (= 200 (:status resp))
             (fetch-deps-via-index
              #(:body (http/get (str peer-url "/cid/" %) {:as :byte-array}))
              (get-in resp [:body :index-cid])
              target-path)))
         (catch Exception e
           (println (str "batch: remote fetch failed (" peer-url "): " (.getMessage e)))
           nil))))))

;; ---------------------------------------------------------------------------
;; Transitive dep collection

(defn- collect-transitive-deps
  "For user-type relations in matching contexts, fetches their deps and returns
   [additional-hops source-cids]."
  [server dr context-path current-hop]
  (let [relations (->> (get dr "dr/contexts" [])
                       (filter #(= (get % "dr-ctx/path") context-path))
                       (mapcat #(get % "dr-ctx/relations" []))
                       (filter #(= (get % "dr-rel/type") "user")))]
    (reduce
     (fn [[hops srcs] rel]
       (let [max-depth (min 10 (max 2 (get rel "dr-rel-user/transitive-depth" 2)))]
         (if (>= current-hop max-depth)
           [hops srcs]
           (if-let [[deps deps-cid]
                    (try (fetch-user-deps server
                                          (get rel "dr-rel-user/user-id")
                                          (or (seq (get rel "dr-rel-user/context-path")) context-path))
                         (catch Exception _ nil))]
             [(into hops (adjust-hops current-hop (get deps "crd/hops" [])))
              (conj srcs deps-cid)]
             [hops srcs]))))
     [[] []]
     relations)))

;; ---------------------------------------------------------------------------
;; Compute and publish for one user

(defn- compute-context-deps
  "Computes a single ContextRelationsDeps for a user+context."
  [server user context-path now]
  (let [dr-cid   (:latest-dr-cid user)
        dr-bytes (ipfs/cat (:ipfs server) dr-cid)
        arch-cid (ipfs/add (:ipfs server) (zip-archive [[dr-cid dr-bytes]]))
        hop1     {"crd-hop/hop"             1
                  "crd-hop/dr-addresses"    [dr-cid]
                  "crd-hop/archive-address" arch-cid
                  "crd-hop/size"            (alength ^bytes dr-bytes)}
        [extra-hops src-cids] (collect-transitive-deps server (:latest-dr user) context-path 1)]
    (make-crd (:user-id (:key-pair user)) now context-path (into [hop1] extra-hops) src-cids)))

(defn- process-user! [server user]
  (let [dr     (:latest-dr user)
        dr-cid (:latest-dr-cid user)
        kp     (:key-pair user)]
    (when (and dr (seq dr-cid))
      (let [now       (System/nanoTime)
            uid       (:user-id kp)
            idx-ctxs  (mapv (fn [ctx]
                              (let [cpath    (get ctx "dr-ctx/path")
                                    deps     (compute-context-deps server user cpath now)
                                    deps-cid (add-document! server deps)
                                    dep-hops (get deps "crd/hops" [])]
                                {"crd-idx-ctx/path"        cpath
                                 "crd-idx-ctx/crd-address" deps-cid
                                 "crd-idx-ctx/hops"        (reduce max 0 (map #(get % "crd-hop/hop" 0) dep-hops))
                                 "crd-idx-ctx/size"        (reduce + 0 (map #(get % "crd-hop/size" 0) dep-hops))}))
                            (get dr "dr/contexts" []))
            index-cid (add-document! server {"crd-idx/version"      1
                                             "crd-idx/timestamp-ns" now
                                             "crd-idx/user-id"      uid
                                             "crd-idx/contexts"     idx-ctxs})
            user-info {"user/version"         1
                       "user/timestamp-ns"    now
                       "user/user-id"         uid
                       "user/user-public-key" (:encoded-public-key kp)
                       "user/dr-address"      dr-cid}
            ui-cid    (add-document! server (schema/wrap-envelope user-info kp))]
        (state/set-index-cid! server uid index-cid)
        (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid)))))

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
