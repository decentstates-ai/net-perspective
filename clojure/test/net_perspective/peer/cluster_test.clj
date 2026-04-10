(ns net-perspective.peer.cluster-test
  "Integration tests using a real kubo daemon.
   Requires `ipfs` binary on PATH — run inside `nix develop`.
   Mirrors the Go cluster_test.go scenarios."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]
            [ring.adapter.jetty :as jetty]
            [net-perspective.crypto :as crypto]
            [net-perspective.schema :as schema]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry]
            [net-perspective.peer.handler :as handler]
            [net-perspective.peer.batch :as batch])
  (:import [java.io File]
           [java.net ServerSocket]
           [org.eclipse.jetty.server Server]))

;; ---------------------------------------------------------------------------
;; IPFS daemon lifecycle

(defn- free-port []
  (let [s (ServerSocket. 0)
        p (.getLocalPort s)]
    (.close s)
    p))

(defn- ipfs-available? []
  (= 0 (:exit (sh "which" "ipfs"))))

(defn- start-ipfs-daemon
  "Starts a private kubo daemon in a temp dir. Returns {:client :stop-fn}."
  []
  (when-not (ipfs-available?)
    (throw (ex-info "ipfs binary not found in PATH" {})))
  (let [repo-dir (doto (File. (System/getProperty "java.io.tmpdir")
                               (str "np-test-ipfs-" (System/nanoTime)))
                   (.mkdirs))
        env      (into (System/getenv)
                       {"IPFS_PATH" (.getAbsolutePath repo-dir)})
        run!     (fn [& args]
                   (let [r (apply sh "ipfs" (concat args [:env env]))]
                     (when (not= 0 (:exit r))
                       (throw (ex-info (str "ipfs " args " failed") {:out (:out r) :err (:err r)})))))
        api-port  (free-port)
        swarm-port (free-port)
        gw-port    (free-port)]
    (run! "init" "--profile=test" "--empty-repo")
    (run! "config" "Addresses.API"
          (str "/ip4/127.0.0.1/tcp/" api-port))
    (run! "config" "Addresses.Gateway"
          (str "/ip4/127.0.0.1/tcp/" gw-port))
    (run! "config" "--json" "Addresses.Swarm"
          (json/generate-string [(str "/ip4/127.0.0.1/tcp/" swarm-port)]))
    (let [pb      (doto (ProcessBuilder. ["ipfs" "daemon" "--offline"])
                    (.redirectErrorStream true))
          _       (doto (.environment pb) (.putAll env))
          process (.start pb)
          client  (ipfs/new-client (str "localhost:" api-port))
          deadline (+ (System/currentTimeMillis) 30000)]
      ;; Poll until responsive.
      (loop []
        (when (> (System/currentTimeMillis) deadline)
          (.destroy process)
          (throw (ex-info "IPFS daemon did not start within 30s" {})))
        (Thread/sleep 300)
        (when-not (ipfs/ping client)
          (recur)))
      {:client  client
       :stop-fn (fn []
                  (.destroy process)
                  (.waitFor process)
                  (doseq [f (reverse (file-seq repo-dir))]
                    (.delete ^File f)))})))

;; ---------------------------------------------------------------------------
;; Cluster helpers

(defn- new-peer-server [ipfs-client reg]
  (let [kp  (crypto/generate-key-pair)
        srv (-> (state/new-server ipfs-client ":0" kp)
                (assoc :registry reg))]
    srv))

(defn- start-http [server]
  (let [port   (free-port)
        h      (handler/make-handler server)
        jetty  (jetty/run-jetty h {:port port :join? false})]
    {:jetty    jetty
     :base-url (str "http://localhost:" port)
     :port     port}))

(defn- add-user! [ipfs-client server reg base-url]
  (let [kp       (crypto/generate-key-pair)
        key-name (str "user-" (schema/b64-encode (:user-id kp)))
        ipns-addr (ipfs/key-gen ipfs-client key-name)]
    (state/add-homed-user! server {:key-pair      kp
                                    :ipns-key-name key-name
                                    :ipns-address  ipns-addr
                                    :latest-dr     nil
                                    :latest-dr-cid ""
                                    :index-cid     ""})
    (registry/register! reg (:user-id kp) base-url)
    kp))

(defn- submit! [server kp dr-map]
  (let [dr-env (schema/wrap dr-map kp)
        ui     {"user-info/version"         1
                "user-info/timestamp-ns"     (get dr-map "direct-relations/timestamp-ns")
                "user-info/user-id"          (:user-id kp)
                "user-info/user-public-key"  (:encoded-public-key kp)}
        ui-env (schema/wrap ui kp)]
    ;; Call the handler directly rather than over HTTP for speed.
    (let [user-id (:user-id kp)
          dr-bytes (schema/marshal dr-env)
          dr-cid   (ipfs/add (:ipfs server) dr-bytes)
          ui-bytes (schema/marshal ui-env)
          ui-cid   (ipfs/add (:ipfs server) ui-bytes)]
      (ipfs/publish-ipns (:ipfs server) (str "user-" (schema/b64-encode user-id)) ui-cid)
      (state/store-dr! server user-id dr-map dr-cid))))

(defn- run-batch-rounds! [servers n]
  (dotimes [_ n]
    (doseq [srv servers]
      (batch/run-batch! srv))))

(defn- fetch-index [server kp]
  (let [user    (state/get-user server (:user-id kp))
        idx-cid (:index-cid user)]
    (when (seq idx-cid)
      (schema/unmarshal (ipfs/cat (:ipfs server) idx-cid)))))

(defn- fetch-deps [server kp context-path]
  (when-let [index (fetch-index server kp)]
    (when-let [entry (->> (get index "context-relations-deps-index/contexts" [])
                          (filter #(= (vec (get % "context-relations-deps-index-context/context-path"))
                                      (vec context-path)))
                          first)]
      (let [deps-cid (get entry "context-relations-deps-index-context/context-relations-deps-content-address")]
        (schema/unmarshal (ipfs/cat (:ipfs server) deps-cid))))))

(defn- all-dr-cids [deps]
  (->> (get deps "context-relations-deps/hops" [])
       (mapcat #(get % "context-relations-deps-hop/direct-relations-addresses" []))
       set))

(defn- make-dr [kp & {:keys [ts] :or {ts (System/nanoTime)}}]
  {"direct-relations/direct-relations-version" 1
   "direct-relations/timestamp-ns"             ts
   "direct-relations/user-id"                  (:user-id kp)
   "direct-relations/contexts"                 []})

;; ---------------------------------------------------------------------------
;; Tests

(defmacro with-ipfs [[binding] & body]
  `(if-not (ipfs-available?)
     (println "SKIP: ipfs binary not found")
     (let [daemon# (start-ipfs-daemon)
           ~binding (:client daemon#)]
       (try
         ~@body
         (finally
           ((:stop-fn daemon#)))))))

(deftest test-local-chain
  (with-ipfs [ipfs-client]
    (let [reg    (registry/new-registry)
          srv    (new-peer-server ipfs-client reg)
          http   (start-http srv)
          alice  (add-user! ipfs-client srv reg (:base-url http))
          bob    (add-user! ipfs-client srv reg (:base-url http))]
      (try
        (submit! srv bob
                 (assoc (make-dr bob)
                        "direct-relations/contexts"
                        [{"direct-relations-context/context-path" ["food"]
                          "direct-relations-context/relations"
                          [{"direct-relations-rel/type"    "uri"
                            "direct-relations-rel-uri/uri" "https://bob.example.com"}]}]))
        (submit! srv alice
                 (assoc (make-dr alice)
                        "direct-relations/contexts"
                        [{"direct-relations-context/context-path" ["food"]
                          "direct-relations-context/relations"
                          [{"direct-relations-rel/type"    "uri"
                            "direct-relations-rel-uri/uri" "https://alice.example.com"}
                           {"direct-relations-rel/type"                  "user"
                            "direct-relations-rel-user/user-id"          (:user-id bob)
                            "direct-relations-rel-user/transitive-depth" 2}]}]))
        (run-batch-rounds! [srv] 2)
        (let [deps (fetch-deps srv alice ["food"])]
          (testing "at least 2 hops"
            (is (>= (count (get deps "context-relations-deps/hops" [])) 2)))
          (testing "at least 2 distinct DR CIDs"
            (is (>= (count (all-dr-cids deps)) 2))))
        (finally
          (.stop ^Server (:jetty http)))))))

(deftest test-context-isolation
  (with-ipfs [ipfs-client]
    (let [reg   (registry/new-registry)
          srv   (new-peer-server ipfs-client reg)
          http  (start-http srv)
          alice (add-user! ipfs-client srv reg (:base-url http))
          bob   (add-user! ipfs-client srv reg (:base-url http))]
      (try
        (submit! srv bob
                 (assoc (make-dr bob)
                        "direct-relations/contexts"
                        [{"direct-relations-context/context-path" ["food"]
                          "direct-relations-context/relations"
                          [{"direct-relations-rel/type"    "uri"
                            "direct-relations-rel-uri/uri" "https://bob-food.example.com"}]}
                         {"direct-relations-context/context-path" ["news"]
                          "direct-relations-context/relations"
                          [{"direct-relations-rel/type"    "uri"
                            "direct-relations-rel-uri/uri" "https://bob-news.example.com"}]}]))
        (submit! srv alice
                 (assoc (make-dr alice)
                        "direct-relations/contexts"
                        [{"direct-relations-context/context-path" ["food"]
                          "direct-relations-context/relations"
                          [{"direct-relations-rel/type"                  "user"
                            "direct-relations-rel-user/user-id"          (:user-id bob)
                            "direct-relations-rel-user/transitive-depth" 2}]}
                         {"direct-relations-context/context-path" ["news"]
                          "direct-relations-context/relations"
                          [{"direct-relations-rel/type"    "uri"
                            "direct-relations-rel-uri/uri" "https://alice-news.example.com"}]}]))
        (run-batch-rounds! [srv] 2)
        (let [bob-user   (state/get-user srv (:user-id bob))
              bob-dr-cid (:latest-dr-cid bob-user)
              food-deps  (fetch-deps srv alice ["food"])
              news-deps  (fetch-deps srv alice ["news"])
              food-cids  (all-dr-cids food-deps)
              news-cids  (all-dr-cids news-deps)]
          (testing "Bob's DR CID appears in food deps"
            (is (contains? food-cids bob-dr-cid)))
          (testing "Bob's DR CID does NOT appear in news deps"
            (is (not (contains? news-cids bob-dr-cid)))))
        (finally
          (.stop ^Server (:jetty http)))))))

(deftest test-depth-cap
  (with-ipfs [ipfs-client]
    (let [reg   (registry/new-registry)
          srv   (new-peer-server ipfs-client reg)
          http  (start-http srv)
          n     12
          users (mapv (fn [_] (add-user! ipfs-client srv reg (:base-url http))) (range n))]
      (try
        ;; Each user relates to the next with depth 10.
        (doseq [i (range n)]
          (let [kp   (nth users i)
                rels (cond-> [{"direct-relations-rel/type"    "uri"
                               "direct-relations-rel-uri/uri" "https://node.example.com"}]
                       (< i (dec n))
                       (conj {"direct-relations-rel/type"                  "user"
                              "direct-relations-rel-user/user-id"          (:user-id (nth users (inc i)))
                              "direct-relations-rel-user/transitive-depth" 10}))]
            (submit! srv kp
                     (assoc (make-dr kp)
                            "direct-relations/contexts"
                            [{"direct-relations-context/context-path" ["food"]
                              "direct-relations-context/relations"    rels}]))))
        (run-batch-rounds! [srv] 11)
        (let [deps (fetch-deps srv (first users) ["food"])]
          (testing "no hop exceeds 10"
            (doseq [h (get deps "context-relations-deps/hops" [])]
              (is (<= (get h "context-relations-deps-hop/hop") 10)))))
        (finally
          (.stop ^Server (:jetty http)))))))
