(ns net-perspective.main
  "Single entrypoint. Dispatches on first argument:
     peer          – start peer server + batch scheduler
     init          – generate key pair and empty direct-relations
     submit        – sign and submit direct-relations to peers
     fetch-index   – fetch and print context-relations-deps-index"
  (:require [clojure.tools.cli :refer [parse-opts]]
            [cheshire.core :as json]
            [net-perspective.crypto :as crypto]
            [net-perspective.schema :as schema]
            [net-perspective.util :as util]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry]
            [net-perspective.peer.handler :as handler]
            [net-perspective.peer.scheduler :as scheduler]
            [ring.adapter.jetty :as jetty])
  (:import [java.io File]
           [java.nio.file Files StandardOpenOption]
           [org.eclipse.jetty.server Server])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Key persistence

(defn- key-file ^File [dir] (File. dir "key.seed"))
(defn- dr-file  ^File [dir] (File. dir "direct-relations.json"))

(defn- load-or-create-key [dir]
  (let [f (key-file dir)]
    (if (.exists f)
      (let [seed (Files/readAllBytes (.toPath f))]
        (crypto/key-pair-from-seed seed))
      (let [kp   (crypto/generate-key-pair)
            seed (:private-seed kp)]
        (.mkdirs (File. dir))
        (Files/write (.toPath f) ^bytes seed
                     (into-array StandardOpenOption
                                 [StandardOpenOption/CREATE
                                  StandardOpenOption/TRUNCATE_EXISTING]))
        (println (str "Generated new key pair, saved to " (.getPath f)))
        kp))))

;; ---------------------------------------------------------------------------
;; Subcommands

;; ---- peer ------------------------------------------------------------------

(def peer-opts
  [["-i" "--ipfs ADDR"    "IPFS API address" :default "localhost:5001"]
   ["-l" "--listen ADDR"  "HTTP listen address" :default ":8080"]
   ["-d" "--dir DIR"      "Config directory" :default ".np"]
   ["-h" "--help"]])

(defn cmd-peer [args]
  (let [{:keys [options]} (parse-opts args peer-opts)
        {:keys [ipfs listen dir]} options
        kp     (load-or-create-key dir)
        ipfs-c (ipfs/new-client ipfs)
        reg    (registry/new-registry)
        srv    (-> (state/new-server ipfs-c listen kp)
                   (assoc :registry reg))
        stop   (scheduler/run-scheduler! srv)
        h      (handler/make-handler srv)]
    (println (str "peer identity: " (util/bytes->hex (:user-id kp))))
    (println (str "listening on " listen))
    (let [port  (Integer/parseInt (if (.startsWith ^String listen ":") (subs listen 1) listen))
          jetty (jetty/run-jetty h {:port port :join? false})]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn []
                                             (stop)
                                             (.stop ^Server jetty))))
      (.join ^Server jetty))))

;; ---- init ------------------------------------------------------------------

(def init-opts
  [["-d" "--dir DIR" "Config directory" :default ".np"]
   ["-h" "--help"]])

(defn cmd-init [args]
  (let [{:keys [options]} (parse-opts args init-opts)
        dir (:dir options)
        kp  (load-or-create-key dir)
        f   (dr-file dir)]
    (when-not (.exists f)
      (spit f (json/generate-string
               {"dr/version"    1
                "dr/timestamp-ns" 0
                "dr/user-id"    (util/b64-encode (:user-id kp))
                "dr/contexts"   []}
               {:pretty true}))
      (println (str "Created empty direct-relations at " (.getPath f))))
    (println (str "user-id: " (util/bytes->hex (:user-id kp))))))

;; ---- submit ----------------------------------------------------------------

(def submit-opts
  [["-d" "--dir DIR"       "Config directory" :default ".np"]
   ["-p" "--peers PEERS"   "Comma-separated peer base URLs"]
   ["-h" "--help"]])

(defn cmd-submit [args]
  (let [{:keys [options]} (parse-opts args submit-opts)
        {:keys [dir peers]} options
        kp         (load-or-create-key dir)
        dr-raw     (json/parse-string (slurp (dr-file dir)))
        dr         (assoc dr-raw
                          "dr/user-id"      (:user-id kp)
                          "dr/timestamp-ns" (System/nanoTime))
        dr-env     (schema/wrap dr kp)
        ui         {"user/version"        1
                    "user/timestamp-ns"   (get dr "dr/timestamp-ns")
                    "user/user-id"        (:user-id kp)
                    "user/user-public-key" (:encoded-public-key kp)}
        ui-env     (schema/wrap ui kp)
        body       (json/generate-string {"user-env" ui-env
                                          "dr-env"   dr-env})
        peer-list  (if peers (clojure.string/split peers #",") [])]
    (doseq [peer peer-list]
      (let [url  (str (util/ensure-http peer) "/submit")]
        (try
          (let [resp (clj-http.client/post url
                                           {:body         body
                                            :content-type :json
                                            :as           :json})]
            (if (= 200 (:status resp))
              (println (str "submitted to " peer))
              (println (str "peer " peer " returned " (:status resp)))))
          (catch Exception e
            (println (str "peer " peer " error: " (.getMessage e)))))))))

;; ---- fetch-index -----------------------------------------------------------

(def fetch-opts
  [["-p" "--peer PEER"  "Peer base URL to query"]
   ["-d" "--dir DIR"    "Config directory (for default peer)" :default ".np"]
   ["-h" "--help"]])

(defn cmd-fetch-index [args]
  (let [[ipns-addr & rest-args] args
        {:keys [options]}       (parse-opts rest-args fetch-opts)
        peer-base               (:peer options)]
    (when-not ipns-addr
      (println "usage: fetch-index <ipns-address> [--peer <url>]")
      (System/exit 1))
    (let [base  (util/ensure-http peer-base)
          ;; Resolve IPNS to get user-info
          ui-raw (-> (clj-http.client/get (str base "/user/" ipns-addr) {:as :json})
                     :body)
          env    ui-raw
          ui     (schema/unwrap env)
          hex-id (util/bytes->hex (util/ensure-bytes (get ui "user/user-id")))
          ;; Get index CID from status endpoint
          st     (-> (clj-http.client/get (str base "/status/users/" hex-id) {:as :json})
                     :body)
          idx-cid (get st "index-cid")]
      (if (empty? idx-cid)
        (println "no index published yet")
        (let [index (-> (clj-http.client/get (str base "/cid/" idx-cid) {:as :json})
                        :body)]
          (println (json/generate-string index {:pretty true})))))))

;; ---------------------------------------------------------------------------
;; Entrypoint

(defn -main [& args]
  (let [[cmd & rest] args]
    (case cmd
      "peer"        (cmd-peer rest)
      "init"        (cmd-init rest)
      "submit"      (cmd-submit rest)
      "fetch-index" (cmd-fetch-index rest)
      (do (println "usage: net-perspective <peer|init|submit|fetch-index> [options]")
          (System/exit 1)))))
