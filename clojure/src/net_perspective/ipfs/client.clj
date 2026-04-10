(ns net-perspective.ipfs.client
  "Thin HTTP client for the kubo IPFS RPC API.
   All kubo RPC endpoints are POST requests."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Protocol — implemented by Client (real kubo) and MemStore (tests)

(defprotocol Store
  (add          [store data]         "Store bytes; returns CID string.")
  (cat          [store cid]          "Fetch bytes for a CID.")
  (publish-ipns [store key-name cid] "Publish CID under IPNS key-name.")
  (resolve-ipns [store ipns-addr]    "Resolve IPNS address to CID.")
  (key-gen      [store key-name]     "Idempotently create ed25519 IPNS key; returns IPNS address.")
  (ping         [store]              "Returns true if daemon reachable."))

;; ---------------------------------------------------------------------------
;; Real kubo HTTP client

(defrecord Client [base-url])

(defn new-client
  "Creates a Client pointed at the kubo RPC API.
   addr e.g. \"localhost:5001\" or \"http://localhost:5001\"."
  [addr]
  (let [base (if (.startsWith ^String addr "http") addr (str "http://" addr))]
    (->Client (str base "/api/v0"))))

(extend-type Client
  Store
  (add [client data]
    (let [resp (http/post (str (:base-url client) "/add")
                          {:multipart [{:name      "file"
                                        :content   data
                                        :mime-type "application/octet-stream"}]
                           :as        :json})]
      (get-in resp [:body :Hash])))

  (cat [client cid]
    (:body (http/post (str (:base-url client) "/cat")
                      {:query-params {"arg" cid}
                       :as           :byte-array})))

  (publish-ipns [client key-name cid]
    (http/post (str (:base-url client) "/name/publish")
               {:query-params {"key"           key-name
                               "arg"           (str "/ipfs/" cid)
                               "allow-offline" "true"}
                :as           :json})
    nil)

  (resolve-ipns [client ipns-addr]
    (let [resp (http/post (str (:base-url client) "/name/resolve")
                          {:query-params {"arg" ipns-addr}
                           :as           :json})
          path (get-in resp [:body :Path])]
      (when-not path
        (throw (ex-info "IPNS resolve returned no Path" {:addr ipns-addr})))
      (let [prefix "/ipfs/"]
        (if (.startsWith ^String path prefix)
          (subs path (count prefix))
          (throw (ex-info "unexpected IPNS resolve result" {:path path}))))))

  (key-gen [client key-name]
    (let [existing (get-in (http/post (str (:base-url client) "/key/list")
                                      {:as :json})
                           [:body :Keys])]
      (if-let [found (first (filter #(= (:Name %) key-name) existing))]
        (:Id found)
        (get-in (http/post (str (:base-url client) "/key/gen")
                           {:query-params {"arg" key-name "type" "ed25519"}
                            :as           :json})
                [:body :Id]))))

  (ping [client]
    (try
      (http/post (str (:base-url client) "/id") {:as :json})
      true
      (catch Exception _ false))))

;; ---------------------------------------------------------------------------
;; In-memory store for unit tests

(defrecord MemStore [data ipns counter])

(defn new-mem-store []
  (->MemStore (atom {}) (atom {}) (atom 0)))

(extend-type MemStore
  Store
  (add [store data]
    (let [cid (format "bafytest%016d" (swap! (:counter store) inc))]
      (swap! (:data store) assoc cid data)
      cid))

  (cat [store cid]
    (or (get @(:data store) cid)
        (throw (ex-info (str "not found: " cid) {:cid cid}))))

  (publish-ipns [store key-name cid]
    (swap! (:ipns store) assoc key-name cid)
    nil)

  (resolve-ipns [store ipns-addr]
    (or (get @(:ipns store) ipns-addr)
        (throw (ex-info (str "IPNS not found: " ipns-addr) {:addr ipns-addr}))))

  (key-gen [_store key-name]
    (str "k51qtest-" key-name))

  (ping [_store] true))
