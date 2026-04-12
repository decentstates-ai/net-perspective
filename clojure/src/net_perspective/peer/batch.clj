(ns net-perspective.peer.batch
  "Inductive batch computation of context-relations-deps for all homed users."
  (:require [malli.core :as m]
            [net-perspective.schema :as schema]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipOutputStream ZipEntry]))

;; ---------------------------------------------------------------------------
;; Document IO — validate on store and fetch

(defn- store-document!
  "Validates document against schema-var, marshals to JCS, stores in IPFS. Returns CID."
  [server document schema-var]
  (schema/validate! @schema-var document)
  (let [store (:ipfs server)]
    (ipfs/add store (schema/marshal-document document))))

(defn- fetch-document!
  "Fetches CID from IPFS, unmarshals, validates against schema-var."
  [server cid schema-var]
  (let [store (:ipfs server)]
    (schema/validate! @schema-var (schema/unmarshal-document (ipfs/cat store cid)))))

;; ---------------------------------------------------------------------------
;; CID resolution (state lookups, no IPFS IO)

(defn- user-index-cid
  "Returns the index CID for a user-id (bytes or base64), or nil.
   Checks local state first, then the registry."
  [server user-id]
  (let [uid (schema/ensure-bytes user-id)]
    (or (some-> (state/get-user server uid) :index-cid not-empty)
        (some-> (:registry server) (registry/lookup uid) not-empty))))

(defn- find-deps-cid
  "Returns the crd-address for target-path in a parsed index, or nil."
  [index target-path]
  (some #(when (= (get % "crd-idx-ctx/path") target-path)
           (get % "crd-idx-ctx/crd-address"))
        (get index "crd-idx/contexts" [])))

;; ---------------------------------------------------------------------------
;; Pure document builders (adjust-hops, make-crd, find-deps-cid are tested directly)

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

(defn- zip-archive ^bytes [entries]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [zos (ZipOutputStream. baos)]
      (doseq [[name ^bytes data] entries]
        (.putNextEntry zos (ZipEntry. ^String name))
        (.write zos data)
        (.closeEntry zos)))
    (.toByteArray baos)))

;; ---------------------------------------------------------------------------
;; Fetch — read all data needed for one user's batch computation

(defn- fetch-related-deps
  "For each user-type relation in matching contexts, resolves and fetches their
   deps document. Returns a seq of {:deps doc, :deps-cid cid} maps."
  [server dr context-path current-hop]
  (let [relations (->> (get dr "dr/contexts" [])
                       (filter #(= (get % "dr-ctx/path") context-path))
                       (mapcat #(get % "dr-ctx/relations" []))
                       (filter #(= (get % "dr-rel/type") "user")))]
    (reduce
     (fn [acc rel]
       (let [max-depth (min 10 (max 2 (get rel "dr-rel-user/transitive-depth" 2)))]
         (if (>= current-hop max-depth)
           acc
           (let [rel-uid     (get rel "dr-rel-user/user-id")
                 target-path (or (seq (get rel "dr-rel-user/context-path")) context-path)]
             (if-let [idx-cid (user-index-cid server rel-uid)]
               (try
                 (let [index    (fetch-document! server idx-cid #'schema/ContextRelationsDepsIndex)
                       deps-cid (find-deps-cid index target-path)]
                   (if deps-cid
                     (conj acc {:deps     (fetch-document! server deps-cid #'schema/ContextRelationsDeps)
                                :deps-cid deps-cid})
                     acc))
                 (catch Exception _ acc))
               acc)))))
     []
     relations)))

(defn- fetch-user-data
  "Reads all data from IPFS needed for a user's batch computation.
   Returns a map with :dr-bytes, :arch-cid, and per-context :related-deps,
   or nil if the user has no DR to process."
  [server user]
  (let [dr     (:latest-dr user)
        dr-cid (:latest-dr-cid user)]
    (when (and dr (seq dr-cid))
      (let [store    (:ipfs server)
            dr-bytes (ipfs/cat store dr-cid)
            arch-cid (ipfs/add store (zip-archive [[dr-cid dr-bytes]]))]
        {:dr-bytes     dr-bytes
         :arch-cid     arch-cid
         :context-deps (into {}
                             (map (fn [ctx]
                                    [(get ctx "dr-ctx/path")
                                     (fetch-related-deps server dr (get ctx "dr-ctx/path") 1)]))
                             (get dr "dr/contexts" []))}))))

;; ---------------------------------------------------------------------------
;; Build — document creation from fetched data, CIDs computed from content

(defn- document-cid
  "Validates, marshals to JCS, and computes the CID without storing."
  [server document schema-var]
  (schema/validate! @schema-var document)
  (ipfs/compute-cid (:ipfs server) (schema/marshal-document document)))

(defn- build-context-crd
  "Builds a CRD document for one context from fetched data."
  [uid now dr-cid arch-cid dr-size related-deps context-path]
  (let [hop1       {"crd-hop/hop"             1
                    "crd-hop/dr-addresses"    [dr-cid]
                    "crd-hop/archive-address" arch-cid
                    "crd-hop/size"            dr-size}
        extra-hops (mapcat #(adjust-hops 1 (get (:deps %) "crd/hops" [])) related-deps)
        src-cids   (mapv :deps-cid related-deps)]
    (make-crd uid now context-path (into [hop1] extra-hops) src-cids)))

(defn- build-documents
  "Builds all documents for one user from fetched data.
   Computes CIDs from content so the index and envelope are complete.
   Returns {:crds [{:crd :crd-cid}], :index, :envelope}."
  [server user fetched-data now]
  (let [dr      (:latest-dr user)
        dr-cid  (:latest-dr-cid user)
        kp      (:key-pair user)
        uid     (:user-id kp)
        dr-size (alength ^bytes (:dr-bytes fetched-data))

        crds (mapv (fn [ctx]
                     (let [cpath   (get ctx "dr-ctx/path")
                           related (get (:context-deps fetched-data) cpath [])
                           crd     (build-context-crd uid now dr-cid (:arch-cid fetched-data)
                                                      dr-size related cpath)
                           crd-cid (document-cid server crd #'schema/ContextRelationsDeps)
                           dep-hops (get crd "crd/hops" [])]
                       {:crd     crd
                        :crd-cid crd-cid
                        :idx-ctx {"crd-idx-ctx/path"        cpath
                                  "crd-idx-ctx/crd-address" crd-cid
                                  "crd-idx-ctx/hops"        (reduce max 0 (map #(get % "crd-hop/hop" 0) dep-hops))
                                  "crd-idx-ctx/size"        (reduce + 0 (map #(get % "crd-hop/size" 0) dep-hops))}}))
                   (get dr "dr/contexts" []))

        index     {"crd-idx/version"      1
                   "crd-idx/timestamp-ns" now
                   "crd-idx/user-id"      uid
                   "crd-idx/contexts"     (mapv :idx-ctx crds)}

        user-info {"user/version"         1
                   "user/timestamp-ns"    now
                   "user/user-id"         uid
                   "user/user-public-key" (:encoded-public-key kp)
                   "user/dr-address"      dr-cid}
        envelope  (schema/wrap-envelope user-info kp)]
    {:crds     crds
     :index    index
     :envelope envelope}))

;; ---------------------------------------------------------------------------
;; Store — persist built documents to IPFS and update state

(defn- store-user-documents!
  "Stores all pre-built documents for one user to IPFS, updates state, publishes IPNS."
  [server user {:keys [crds index envelope]}]
  (doseq [{:keys [crd]} crds]
    (store-document! server crd #'schema/ContextRelationsDeps))
  (let [index-cid (store-document! server index #'schema/ContextRelationsDepsIndex)
        ui-cid    (store-document! server envelope #'schema/Envelope)]
    (state/set-index-cid! server (:user-id (:key-pair user)) index-cid)
    (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid)))


;; ---------------------------------------------------------------------------
;; Orchestration

(defn- process-user! [server user]
  (when-let [fetched (fetch-user-data server user)]
    (let [docs (build-documents server user fetched (System/nanoTime))]
      (store-user-documents! server user docs))))

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
