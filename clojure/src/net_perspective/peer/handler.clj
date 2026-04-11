(ns net-perspective.peer.handler
  "Ring HTTP handlers and Reitit routes for the peer server."
  (:require [reitit.ring :as ring]
            [cheshire.core :as json]
            [malli.core :as m]
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
(m/=> json-resp
      [:function
       [:=> [:cat :any]      #'schema/RingResponse]
       [:=> [:cat :any :int] #'schema/RingResponse]])

(defn- error-resp [status msg]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    msg})
(m/=> error-resp [:=> [:cat :int :string] #'schema/RingResponse])

(defn- parse-body [req]
  (some-> (:body req) slurp (json/parse-string))) ; string keys
(m/=> parse-body [:=> [:cat :map] [:maybe :map]])

;; ---------------------------------------------------------------------------
;; POST /submit
;;
;; Uses a sentinel exception to short-circuit validation with an HTTP response.

(defn- submit-error [status msg]
  (throw (ex-info msg {::resp (error-resp status msg)})))

(defn- parse-submit-body [req]
  (let [body   (parse-body req)
        ui-env (get body "user-env")
        dr-env (get body "dr-env")]
    (when (nil? body)   (submit-error 400 "missing body"))
    (when (nil? ui-env) (submit-error 400 "missing user-env"))
    (when (nil? dr-env) (submit-error 400 "missing dr-env"))
    [ui-env dr-env]))
(m/=> parse-submit-body
      [:=> [:cat :map] [:tuple #'schema/Envelope #'schema/Envelope]])

(defn- unwrap-submit-envelopes [ui-env dr-env]
  (let [ui (try (schema/unwrap-envelope ui-env)
                (catch Exception e (submit-error 400 (str "invalid user-info: " (.getMessage e)))))
        dr (try (schema/unwrap-envelope dr-env)
                (catch Exception e (submit-error 400 (str "invalid direct-relations: " (.getMessage e)))))]
    [ui dr]))
(m/=> unwrap-submit-envelopes
      [:=> [:cat #'schema/Envelope #'schema/Envelope]
       [:tuple #'schema/UserInfo #'schema/DirectRelations]])

(defn- validate-submit-ids [ui dr dr-env]
  (let [ui-uid    (schema/ensure-bytes (get ui "user/user-id"))
        dr-uid    (schema/ensure-bytes (get dr "dr/user-id"))
        dr-signer (schema/ensure-bytes (get dr-env "env/user-id"))]
    (when-not (java.util.Arrays/equals ^bytes ui-uid ^bytes dr-uid)
      (submit-error 400 "user-id mismatch between envelopes"))
    (when-not (java.util.Arrays/equals ^bytes dr-uid ^bytes dr-signer)
      (submit-error 400 "dr envelope signer does not match dr content user-id"))
    ui-uid))
(m/=> validate-submit-ids
      [:=> [:cat #'schema/UserInfo #'schema/DirectRelations #'schema/Envelope]
       #'schema/UserId])

(defn- add-document! [server document]
  (ipfs/add (:ipfs server) (schema/marshal-document document)))
(m/=> add-document! [:=> [:cat #'schema/PeerServer :any] #'schema/Cid])

(defn- store-submit! [server user-id dr ui-env dr-env]
  (let [user (state/get-user server user-id)]
    (when-not user (submit-error 403 "user not homed here"))
    (when (<= (get dr "dr/timestamp-ns" 0)
              (state/dr-timestamp server user-id))
      (submit-error 409 "stale submission"))
    (let [dr-cid (add-document! server dr-env)
          ui-cid (add-document! server ui-env)]
      (ipfs/publish-ipns (:ipfs server) (:ipns-key-name user) ui-cid)
      (state/store-dr! server user-id dr dr-cid)
      (json-resp {"cid" dr-cid}))))
(m/=> store-submit!
      [:=> [:cat #'schema/PeerServer bytes? #'schema/DirectRelations #'schema/Envelope #'schema/Envelope]
       #'schema/RingResponse])

(defn- handle-submit [server req]
  (try
    (let [[ui-env dr-env] (parse-submit-body req)
          [ui dr]         (unwrap-submit-envelopes ui-env dr-env)
          user-id         (validate-submit-ids ui dr dr-env)]
      (store-submit! server user-id dr ui-env dr-env))
    (catch clojure.lang.ExceptionInfo e
      (or (::resp (ex-data e))
          (error-resp 500 (.getMessage e))))))
(m/=> handle-submit [:=> [:cat #'schema/PeerServer :map] #'schema/RingResponse])

;; ---------------------------------------------------------------------------
;; GET /user/:userID  and  GET /cid/:cid

(defn- ipfs-raw-resp
  "Fetches cid from IPFS and returns a 200 response with the raw bytes as body.
   Returns a 404 error-resp on any failure."
  [server cid err-prefix]
  (try
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (String. ^bytes (ipfs/cat (:ipfs server) cid) "UTF-8")}
    (catch Exception e
      (error-resp 404 (str err-prefix ": " (.getMessage e))))))
(m/=> ipfs-raw-resp [:=> [:cat #'schema/PeerServer :string :string] #'schema/RingResponse])

(defn- handle-get-user-info [server req]
  (let [ipns-addr (get-in req [:path-params :userID])]
    (try
      (let [cid (ipfs/resolve-ipns (:ipfs server) ipns-addr)]
        (ipfs-raw-resp server cid "resolve error"))
      (catch Exception e
        (error-resp 404 (str "resolve error: " (.getMessage e)))))))
(m/=> handle-get-user-info [:=> [:cat #'schema/PeerServer :map] #'schema/RingResponse])

(defn- handle-get-by-cid [server req]
  (ipfs-raw-resp server (get-in req [:path-params :cid]) "fetch error"))
(m/=> handle-get-by-cid [:=> [:cat #'schema/PeerServer :map] #'schema/RingResponse])

;; ---------------------------------------------------------------------------
;; Status helpers

(defn user-status
  "Returns a status map for a user-map (used by handlers and tests)."
  [user]
  (let [kp (:key-pair user)
        dr (:latest-dr user)]
    {"user-id"       (schema/bytes->hex (:user-id kp))
     "index-cid"     (or (:index-cid user) "")
     "dr-cid"        (or (:latest-dr-cid user) "")
     "context-count" (count (get dr "dr/contexts" []))
     "timestamp-ns"  (get dr "dr/timestamp-ns" 0)}))
(m/=> user-status [:=> [:cat #'schema/HomedUser] :map])

;; ---------------------------------------------------------------------------
;; GET /status/users

(defn- handle-status-users [server _req]
  (json-resp (mapv user-status (state/all-users server))))
(m/=> handle-status-users [:=> [:cat #'schema/PeerServer :map] #'schema/RingResponse])

;; ---------------------------------------------------------------------------
;; GET /status/users/:userID

(defn- handle-status-user [server req]
  (let [hex-id (get-in req [:path-params :userID])]
    (try
      (let [user-id (schema/hex->bytes hex-id)
            user    (state/get-user server user-id)]
        (if user
          (json-resp (user-status user))
          (error-resp 404 "user not found")))
      (catch Exception _
        (error-resp 400 "invalid user-id")))))
(m/=> handle-status-user [:=> [:cat #'schema/PeerServer :map] #'schema/RingResponse])

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
(m/=> make-handler [:=> [:cat #'schema/PeerServer] fn?])
