(ns net-perspective.main
  "Middleware-based CLI for the net-perspective peer server."
  (:require [babashka.cli :as cli]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [cheshire.core :as json]
            [net-perspective.crypto :as crypto]
            [net-perspective.schema :as schema]
            [net-perspective.util :as util]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.system :as peer-system])
  (:import [java.io File]
           [java.nio.file Files StandardOpenOption])
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
;; Common CLI spec

(def common-spec
  {:dir  {:alias :d :desc "Config directory" :default ".np" :coerce :string}
   :help {:alias :h :desc "Display help."    :default false  :coerce :boolean}})

;; ---------------------------------------------------------------------------
;; Middlewares

(defn middleware-help [handler spec usage]
  (fn [ctx]
    (if (get-in ctx [:opts :help])
      (do (println (str "Usage: net-perspective " usage))
          (println)
          (println (cli/format-opts {:spec spec})))
      (handler ctx))))

(defn middleware-exception [handler]
  (fn [ctx]
    (try (handler ctx)
         (catch Exception e
           (binding [*out* *err*]
             (println "Error:" (ex-message e)))
           (System/exit 1)))))

(defn wrap-middlewares [handler middlewares]
  (reduce (fn [acc mw] (mw acc)) handler (reverse middlewares)))

;; ---------------------------------------------------------------------------
;; peer command

(def peer-spec
  (merge common-spec
         {:ipfs   {:alias :i :desc "IPFS API address"  :default "localhost:5001" :coerce :string}
          :listen {:alias :l :desc "HTTP listen address" :default ":8080"        :coerce :string}}))

(defn cmd-peer [{:keys [opts]}]
  (let [{:keys [ipfs listen dir]} opts
        port (Integer/parseInt (if (str/starts-with? listen ":") (subs listen 1) listen))
        kp   (load-or-create-key dir)]
    (println (str "peer identity: " (schema/bytes->hex (:user-id kp))))
    (println (str "listening on " listen))
    (with-open [sys (peer-system/start! {:ipfs-addr   ipfs
                                         :listen-port port
                                         :self-kp     kp
                                         :var-dir     (str dir "/state")})]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable #(.close sys)))
      (util/wait-forever @sys))))

;; ---------------------------------------------------------------------------
;; init command

(def init-spec common-spec)

(defn cmd-init [{:keys [opts]}]
  (let [{:keys [dir]} opts
        kp (load-or-create-key dir)
        f  (dr-file dir)]
    (when-not (.exists f)
      (spit f (json/generate-string
               {"dr/version"      1
                "dr/timestamp-ns" 0
                "dr/user-id"      (schema/b64-encode (:user-id kp))
                "dr/contexts"     []}
               {:pretty true}))
      (println (str "Created empty direct-relations at " (.getPath f))))
    (println (str "user-id: " (schema/bytes->hex (:user-id kp))))))

;; ---------------------------------------------------------------------------
;; submit command

(def submit-spec
  (merge common-spec
         {:peers {:alias :p :desc "Comma-separated peer base URLs" :coerce :string}}))

(defn cmd-submit [{:keys [opts]}]
  (let [{:keys [dir peers]} opts
        kp        (load-or-create-key dir)
        dr-raw    (json/parse-string (slurp (dr-file dir)))
        dr        (assoc dr-raw
                         "dr/user-id"      (:user-id kp)
                         "dr/timestamp-ns" (System/nanoTime))
        dr-env    (schema/wrap-envelope dr kp)
        ui        {"user/version"         1
                   "user/timestamp-ns"    (get dr "dr/timestamp-ns")
                   "user/user-id"         (:user-id kp)
                   "user/user-public-key" (:encoded-public-key kp)}
        ui-env    (schema/wrap-envelope ui kp)
        body      (json/generate-string {"user-env" ui-env "dr-env" dr-env})
        peer-list (if peers (str/split peers #",") [])]
    (doseq [peer peer-list]
      (let [url (str (schema/ensure-http peer) "/submit")]
        (try
          (let [resp (http/post url {:body body :content-type :json :as :json})]
            (if (= 200 (:status resp))
              (println (str "submitted to " peer))
              (println (str "peer " peer " returned " (:status resp)))))
          (catch Exception e
            (println (str "peer " peer " error: " (.getMessage e)))))))))

;; ---------------------------------------------------------------------------
;; fetch-index command

(def fetch-index-spec
  (merge common-spec
         {:peer {:alias :p :desc "Peer base URL to query" :coerce :string}}))

(defn cmd-fetch-index [{:keys [opts args]}]
  (let [ipns-addr (first args)
        peer-base (:peer opts)]
    (when-not ipns-addr
      (println "usage: fetch-index <ipns-address> [--peer <url>]")
      (System/exit 1))
    (let [base    (schema/ensure-http peer-base)
          ui-raw  (-> (http/get (str base "/user/" ipns-addr) {:as :json}) :body)
          ui      (schema/unwrap-envelope ui-raw)
          hex-id  (schema/bytes->hex (schema/ensure-bytes (get ui "user/user-id")))
          st      (-> (http/get (str base "/status/users/" hex-id) {:as :json}) :body)
          idx-cid (get st "index-cid")]
      (if (empty? idx-cid)
        (println "no index published yet")
        (let [index (-> (http/get (str base "/cid/" idx-cid) {:as :json}) :body)]
          (println (json/generate-string index {:pretty true})))))))

;; ---------------------------------------------------------------------------
;; Dispatch table

(declare dispatch-table)

(defn- print-top-help [_ctx]
  (println "Usage: net-perspective <command> [options]")
  (println)
  (println "Commands:")
  (println (cli/format-table
            {:rows [["peer"        "Start the peer server"]
                    ["init"        "Initialise config directory"]
                    ["submit"      "Sign and submit direct-relations to peers"]
                    ["fetch-index" "Fetch and print a user's context-relations-deps index"]]})))

(def dispatch-table
  [{:cmds ["peer"]
    :fn   (wrap-middlewares cmd-peer [middleware-exception
                                      #(middleware-help % peer-spec "peer [options]")])
    :spec peer-spec}

   {:cmds ["init"]
    :fn   (wrap-middlewares cmd-init [middleware-exception
                                      #(middleware-help % init-spec "init [options]")])
    :spec init-spec}

   {:cmds ["submit"]
    :fn   (wrap-middlewares cmd-submit [middleware-exception
                                        #(middleware-help % submit-spec "submit [options]")])
    :spec submit-spec}

   {:cmds ["fetch-index"]
    :fn   (wrap-middlewares cmd-fetch-index [middleware-exception
                                             #(middleware-help % fetch-index-spec "fetch-index <ipns-address> [options]")])
    :spec fetch-index-spec}

   {:cmds []
    :fn   print-top-help}])

;; ---------------------------------------------------------------------------
;; Entrypoint

(defn -main [& args]
  (cli/dispatch dispatch-table args))
