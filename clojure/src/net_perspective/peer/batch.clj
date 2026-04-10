(ns net-perspective.peer.batch
  "Inductive batch computation of context-relations-deps for all homed users."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [net-perspective.schema :as schema]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Utilities

(defn- path= [a b]
  (= (vec a) (vec b)))

(defn- now-ns [] (System/nanoTime))

(defn- min* [a b] (if (< a b) a b))

;; ---------------------------------------------------------------------------
;; Fetch context deps for a related user

(defn- extract-deps-from-index
  "Given serialised index bytes and a target context path, fetches and returns
   [deps-map deps-cid] using fetch-fn (cid → bytes)."
  [index-bytes target-path fetch-fn]
  (let [index (schema/unmarshal index-bytes)]
    (when-let [entry (->> (get index "context-relations-deps-index/contexts" [])
                          (filter #(path= (get % "context-relations-deps-index-context/context-path")
                                          target-path))
                          first)]
      (let [deps-cid (get entry "context-relations-deps-index-context/context-relations-deps-content-address")]
        (when deps-cid
          [(schema/unmarshal (fetch-fn deps-cid)) deps-cid])))))

(defn- fetch-local-user-context-deps
  "Fetches context deps for a locally-homed user."
  [server related-user target-path]
  (let [index-cid (:index-cid related-user)]
    (when (seq index-cid)
      (let [index-bytes (ipfs/cat (:ipfs server) index-cid)]
        (extract-deps-from-index index-bytes target-path
                                 #(ipfs/cat (:ipfs server) %))))))

(defn- fetch-remote-user-context-deps
  "Fetches context deps for a user homed on a remote peer via HTTP."
  [peer-url ^bytes user-id target-path]
  (let [hex-id  (apply str (map #(format "%02x" (bit-and % 0xFF)) user-id))
        st-resp (try (http/get (str peer-url "/status/users/" hex-id) {:as :json})
                     (catch Exception _ nil))]
    (when (and st-resp (= 200 (:status st-resp)))
      ;; clj-http keywordizes JSON response keys by default.
      (let [index-cid (get-in st-resp [:body :index-cid])]
        (when (seq index-cid)
          (let [fetch-fn (fn [cid]
                           (:body (http/get (str peer-url "/cid/" cid)
                                            {:as :byte-array})))
                index-bytes (fetch-fn index-cid)]
            (extract-deps-from-index index-bytes target-path fetch-fn)))))))

(defn- fetch-user-context-deps
  "Resolves a related user's context-deps, trying local first then remote."
  [server rel-user-id target-path]
  (let [local-user (state/get-user server rel-user-id)]
    (or (when local-user
          (fetch-local-user-context-deps server local-user target-path))
        (when-let [peer-url (some-> (:registry server)
                                    (registry/lookup rel-user-id))]
          (fetch-remote-user-context-deps peer-url rel-user-id target-path))
        nil)))

;; ---------------------------------------------------------------------------
;; Transitive dep fetching

(defn- fetch-transitive-deps
  "Fetches context-deps from directly-related users and returns
   [additional-hops source-cids]."
  [server dr context-path current-hop]
  (let [contexts (filter #(path= (get % "direct-relations-context/context-path")
                                 context-path)
                         (get dr "direct-relations/contexts" []))]
    (reduce
     (fn [[hops srcs] rel]
       (when (not= (get rel "direct-relations-rel/type") "user")
         [hops srcs])
       (let [max-depth  (min* 10 (max 2 (get rel "direct-relations-rel-user/transitive-depth" 2)))
             rel-user-id (get rel "direct-relations-rel-user/user-id")
             target-path (or (seq (get rel "direct-relations-rel-user/context-path"))
                             context-path)]
         (if (>= current-hop max-depth)
           [hops srcs]
           (if-let [[related-deps deps-cid]
                    (try (fetch-user-context-deps server rel-user-id target-path)
                         (catch Exception _ nil))]
             (let [new-hops
                   (reduce
                    (fn [acc h]
                      (let [adjusted-hop (+ current-hop (get h "context-relations-deps-hop/hop" 1))]
                        (if (> adjusted-hop 10)
                          acc
                          (conj acc (assoc h "context-relations-deps-hop/hop" adjusted-hop)))))
                    []
                    (get related-deps "context-relations-deps/hops" []))]
               [(into hops new-hops)
                (if deps-cid (conj srcs deps-cid) srcs)])
             [hops srcs]))))
     [[] []]
     (mapcat #(get % "direct-relations-context/relations" []) contexts))))

;; ---------------------------------------------------------------------------
;; Compute deps for one user+context

(defn- compute-deps
  [server user dr context-path]
  (let [kp      (:key-pair user)
        dr-cid  (:latest-dr-cid user)
        dr-size (try (alength ^bytes (ipfs/cat (:ipfs server) dr-cid))
                     (catch Exception _ 0))
        hop1    {"context-relations-deps-hop/hop"                              1
                 "context-relations-deps-hop/direct-relations-addresses"       [dr-cid]
                 "context-relations-deps-hop/direct-relations-archive-address" dr-cid
                 "context-relations-deps-hop/size"                             dr-size}
        [extra-hops src-cids] (try (fetch-transitive-deps server dr context-path 1)
                                   (catch Exception _ [[] []]))]
    {"context-relations-deps/version"      1
     "context-relations-deps/timestamp-ns" (now-ns)
     "context-relations-deps/user-id"      (:user-id kp)
     "context-relations-deps/context-path" context-path
     "context-relations-deps/hops"         (into [hop1] extra-hops)
     "context-relations-deps/source-context-relations-deps-content-addresses" src-cids}))

;; ---------------------------------------------------------------------------
;; Process one user

(defn- process-user! [server user]
  (let [dr     (:latest-dr user)
        dr-cid (:latest-dr-cid user)
        kp     (:key-pair user)]
    (when (and dr (seq dr-cid))
      (let [now     (now-ns)
            contexts (get dr "direct-relations/contexts" [])
            index-contexts
            (mapv (fn [ctx]
                    (let [cpath (get ctx "direct-relations-context/context-path")
                          deps  (compute-deps server user dr cpath)
                          deps-bytes (schema/marshal deps)
                          deps-cid   (ipfs/add (:ipfs server) deps-bytes)
                          total-size (reduce + 0 (map #(get % "context-relations-deps-hop/size" 0)
                                                      (get deps "context-relations-deps/hops" [])))
                          max-hop    (reduce max 0 (map #(get % "context-relations-deps-hop/hop" 0)
                                                        (get deps "context-relations-deps/hops" [])))]
                      {"context-relations-deps-index-context/context-path"
                       cpath
                       "context-relations-deps-index-context/context-relations-deps-content-address"
                       deps-cid
                       "context-relations-deps-index-context/hops"
                       max-hop
                       "context-relations-deps-index-context/size"
                       total-size}))
                  contexts)
            index      {"context-relations-deps-index/version"      1
                        "context-relations-deps-index/timestamp-ns" now
                        "context-relations-deps-index/user-id"      (:user-id kp)
                        "context-relations-deps-index/contexts"     index-contexts}
            index-bytes (schema/marshal index)
            index-cid   (ipfs/add (:ipfs server) index-bytes)]
        (state/set-index-cid! server (:user-id kp) index-cid)
        ;; Publish updated user-info to IPNS.
        (let [ui      {"user-info/version"                           1
                       "user-info/timestamp-ns"                      now
                       "user-info/user-id"                           (:user-id kp)
                       "user-info/user-public-key"                   (:encoded-public-key kp)
                       "user-info/direct-relations-content-address"  dr-cid}
              ui-env  (schema/wrap ui kp)
              ui-bytes (schema/marshal ui-env)
              ui-cid   (ipfs/add (:ipfs server) ui-bytes)]
          (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid))))))

;; ---------------------------------------------------------------------------
;; Public entry point

(defn run-batch!
  "Runs one batch update round for all homed users."
  [server]
  (doseq [user (state/all-users server)]
    (try (process-user! server user)
         (catch Exception e
           (println (str "batch: user error: " (.getMessage e)))))))
