(ns net-perspective.peer.handler
  "Ring HTTP handlers and Reitit routes for the peer server."
  (:require [reitit.ring :as ring]
            [cheshire.core :as json]
            [net-perspective.schema :as schema]
            [net-perspective.peer.state :as state]
            [net-perspective.ipfs.client :as ipfs]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- json-resp
  ([body] (json-resp body 200))
  ([body status]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    (json/generate-string body)}))

(defn- error-resp [status msg]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    msg})

(defn- parse-body [req]
  (some-> (:body req) slurp (json/parse-string))) ; string keys

(defn- hex->bytes ^bytes [^String s]
  (let [len (/ (count s) 2)
        out (byte-array len)]
    (dotimes [i len]
      (aset out i (unchecked-byte (Integer/parseInt (subs s (* i 2) (+ (* i 2) 2)) 16))))
    out))

(defn- bytes->hex ^String [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xFF)) b)))

;; ---------------------------------------------------------------------------
;; POST /submit
;;
;; Uses a sentinel exception to short-circuit validation with an HTTP response.

(defn- submit-error [status msg]
  (throw (ex-info msg {::resp (error-resp status msg)})))

(defn- handle-submit [server req]
  (try
    (let [body   (parse-body req)
          ui-env (get body "user-info-envelope")
          dr-env (get body "direct-relations-envelope")]
      (when (nil? body)   (submit-error 400 "missing body"))
      (when (nil? ui-env) (submit-error 400 "missing user-info-envelope"))
      (when (nil? dr-env) (submit-error 400 "missing direct-relations-envelope"))
      (let [ui  (try (schema/unwrap ui-env)
                     (catch Exception e (submit-error 400 (str "invalid user-info envelope: " (.getMessage e)))))
            dr  (try (schema/unwrap dr-env)
                     (catch Exception e (submit-error 400 (str "invalid direct-relations envelope: " (.getMessage e)))))
            ui-uid (get ui "user-info/user-id")
            dr-uid (get dr "direct-relations/user-id")]
        (when-not (java.util.Arrays/equals ^bytes ui-uid ^bytes dr-uid)
          (submit-error 400 "user-id mismatch between envelopes"))
        (let [user-id (get ui "user-info/user-id")
              user    (state/get-user server user-id)]
          (when-not user (submit-error 403 "user not homed here"))
          (when (<= (get dr "direct-relations/timestamp-ns" 0)
                    (state/dr-timestamp server user-id))
            (submit-error 409 "stale submission"))
          (let [dr-bytes (schema/marshal dr-env)
                dr-cid   (ipfs/add (:ipfs server) dr-bytes)
                ui-bytes (schema/marshal ui-env)
                ui-cid   (ipfs/add (:ipfs server) ui-bytes)]
            (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid)
            (state/store-dr! server user-id dr dr-cid)
            (json-resp {"cid" dr-cid})))))
    (catch clojure.lang.ExceptionInfo e
      (or (::resp (ex-data e))
          (error-resp 500 (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; GET /user/:userID

(defn- handle-get-user-info [server req]
  (let [ipns-addr (get-in req [:path-params :userID])]
    (try
      (let [cid  (ipfs/resolve-ipns (:ipfs server) ipns-addr)
            data (ipfs/cat (:ipfs server) cid)]
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (String. ^bytes data "UTF-8")})
      (catch Exception e
        (error-resp 404 (str "resolve error: " (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; GET /cid/:cid

(defn- handle-get-by-cid [server req]
  (try
    (let [cid  (get-in req [:path-params :cid])
          data (ipfs/cat (:ipfs server) cid)]
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (String. ^bytes data "UTF-8")})
    (catch Exception e
      (error-resp 404 (str "fetch error: " (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Status helpers

(defn user-status
  "Returns a status map for a user-map (used by handlers and tests)."
  [user]
  (let [kp (:key-pair user)
        dr (:latest-dr user)]
    {"user-id"       (bytes->hex (:user-id kp))
     "index-cid"     (or (:index-cid user) "")
     "dr-cid"        (or (:latest-dr-cid user) "")
     "context-count" (count (get dr "direct-relations/contexts" []))
     "timestamp-ns"  (get dr "direct-relations/timestamp-ns" 0)}))

;; ---------------------------------------------------------------------------
;; GET /status/users

(defn- handle-status-users [server _req]
  (json-resp (mapv user-status (state/all-users server))))

;; ---------------------------------------------------------------------------
;; GET /status/users/:userID

(defn- handle-status-user [server req]
  (let [hex-id (get-in req [:path-params :userID])]
    (try
      (let [user-id (hex->bytes hex-id)
            user    (state/get-user server user-id)]
        (if user
          (json-resp (user-status user))
          (error-resp 404 "user not found")))
      (catch Exception _
        (error-resp 400 "invalid user-id")))))

;; ---------------------------------------------------------------------------
;; Router

(defn make-handler [server]
  (ring/ring-handler
   (ring/router
    [["/submit"              {:post {:handler #(handle-submit server %)}}]
     ["/user/:userID"        {:get  {:handler #(handle-get-user-info server %)}}]
     ["/cid/:cid"            {:get  {:handler #(handle-get-by-cid server %)}}]
     ["/status/users"        {:get  {:handler #(handle-status-users server %)}}]
     ["/status/users/:userID" {:get {:handler #(handle-status-user server %)}}]])
   (ring/create-default-handler)))
