(ns net-perspective.peer.handler-test
  "Unit tests for the peer HTTP handlers using MemStore."
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [net-perspective.test-utils]
            [net-perspective.crypto :as crypto]
            [net-perspective.schema :as schema]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.handler :as handler]))

(net-perspective.test-utils/deftest-ns-schemas-test)

;; ---------------------------------------------------------------------------
;; Test helpers

(defn- make-server
  "Creates a peer Server with a MemStore for IPFS."
  [self-kp]
  (state/->Server (atom {:users {}}) @(ipfs/new-mem-store) nil self-kp ":0"))

(defn- add-user! [server kp]
  (state/add-homed-user! server {:key-pair      kp
                                  :ipns-key-name (str "user-" (schema/b64-encode (:user-id kp)))
                                  :ipns-address  "k51qtest"
                                  :latest-dr     nil
                                  :latest-dr-cid ""
                                  :index-cid     ""}))

(defn- make-dr
  ([kp] (make-dr kp (System/nanoTime)))
  ([kp ts]
   {"dr/version" 1
    "dr/timestamp-ns"             ts
    "dr/user-id"                  (:user-id kp)
    "dr/contexts"
    [{"dr-ctx/path" ["food"]
      "dr-ctx/relations"
      [{"dr-rel/type"    "uri"
        "dr-rel-uri/uri" "https://example.com"}]}]}))

(defn- make-submit-body
  ([kp] (make-submit-body kp (make-dr kp)))
  ([kp dr]
   (let [dr-env (schema/wrap-envelope dr kp)
         ui     {"user/version"         1
                 "user/timestamp-ns"     (get dr "dr/timestamp-ns")
                 "user/user-id"          (:user-id kp)
                 "user/user-public-key"  (:encoded-public-key kp)}
         ui-env (schema/wrap-envelope ui kp)]
     {"user-env"        ui-env
      "dr-env" dr-env})))

(defn- post-submit [h body]
  (h (-> (mock/request :post "/submit")
         (mock/content-type "application/json")
         (mock/body (json/generate-string body)))))

;; ---------------------------------------------------------------------------
;; Tests

(deftest test-submit-accepted
  (let [kp   (crypto/generate-key-pair)
        srv  (make-server kp)
        _    (add-user! srv kp)
        h    (handler/make-handler srv)
        resp (post-submit h (make-submit-body kp))]
    (testing "returns 200"
      (is (= 200 (:status resp))))
    (testing "returns a CID"
      (is (string? (get (json/parse-string (:body resp)) "cid"))))))

(deftest test-submit-stale-rejected
  (let [kp  (crypto/generate-key-pair)
        srv (make-server kp)
        _   (add-user! srv kp)
        h   (handler/make-handler srv)
        ts  (System/nanoTime)
        dr  (make-dr kp ts)
        b   (make-submit-body kp dr)]
    ;; First submit succeeds.
    (is (= 200 (:status (post-submit h b))))
    ;; Exact same timestamp — rejected.
    (testing "returns 409 for same timestamp"
      (is (= 409 (:status (post-submit h b)))))))

(deftest test-submit-unknown-user-rejected
  (let [kp-homed (crypto/generate-key-pair)
        kp-other (crypto/generate-key-pair)
        srv      (make-server kp-homed)
        _        (add-user! srv kp-homed)  ; only kp-homed is registered
        h        (handler/make-handler srv)
        resp     (post-submit h (make-submit-body kp-other))]
    (testing "returns 403"
      (is (= 403 (:status resp))))))

(deftest test-submit-wrong-signature-rejected
  (let [kp-real  (crypto/generate-key-pair)
        kp-other (crypto/generate-key-pair)
        srv      (make-server kp-real)
        _        (add-user! srv kp-real)
        ;; DR content for kp-real but signed by kp-other.
        dr       (make-dr kp-real)
        dr-env   (schema/wrap-envelope dr kp-other)
        ui       {"user/version"         1
                  "user/timestamp-ns"     (get dr "dr/timestamp-ns")
                  "user/user-id"          (:user-id kp-real)
                  "user/user-public-key"  (:encoded-public-key kp-real)}
        ui-env   (schema/wrap-envelope ui kp-real)
        body     {"user-env"        ui-env
                  "dr-env" dr-env}
        h        (handler/make-handler srv)
        resp     (post-submit h body)]
    (testing "returns 400"
      (is (= 400 (:status resp))))))

(deftest test-status-users
  (let [kp  (crypto/generate-key-pair)
        srv (make-server kp)
        _   (add-user! srv kp)
        h   (handler/make-handler srv)
        r   (h (mock/request :get "/status/users"))
        us  (json/parse-string (:body r))]
    (testing "returns 200"
      (is (= 200 (:status r))))
    (testing "lists one user"
      (is (= 1 (count us))))))

(deftest test-status-single-user
  (let [kp     (crypto/generate-key-pair)
        srv    (make-server kp)
        _      (add-user! srv kp)
        h      (handler/make-handler srv)
        hex-id (apply str (map #(format "%02x" (bit-and % 0xFF)) (:user-id kp)))
        r      (h (mock/request :get (str "/status/users/" hex-id)))]
    (testing "returns 200"
      (is (= 200 (:status r))))))
