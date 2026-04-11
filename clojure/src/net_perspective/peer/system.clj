(ns net-perspective.peer.system
  "Composable peer server lifecycle using with-open / closeable.

   Usage:
     (with-open [sys (system/start! config)]
       (lib.system/wait-forever @sys))"
  (:require [malli.core :as m]
            [ring.adapter.jetty :as jetty]
            [net-perspective.schema :as schema]
            [net-perspective.util :as util]
            [net-perspective.ipfs.client :as ipfs]
            [net-perspective.peer.state :as state]
            [net-perspective.peer.registry :as registry]
            [net-perspective.peer.handler :as handler]
            [net-perspective.peer.scheduler :as scheduler])
  (:import [org.eclipse.jetty.server Server]))

(def Config
  "Configuration map for start!."
  (m/schema
   [:map
    [:ipfs-addr  :string]            ; kubo API address, e.g. "localhost:5001"
    [:listen-port :int]              ; HTTP listen port
    [:self-kp    #'schema/KeyPair]
    [:var-dir    {:optional true} [:maybe :string]]]))  ; state cache directory

(defn start!
  "Starts the peer server system. Returns a closeable whose deref is the
   PeerServer. Closing tears down HTTP, scheduler, and IPFS connection
   in reverse order."
  [{:keys [ipfs-addr listen-port self-kp var-dir]}]
  (let [ipfs-c    (ipfs/new-client ipfs-addr)
        reg       (registry/new-registry)
        server    (-> (state/new-server @ipfs-c (str listen-port) self-kp var-dir)
                      (assoc :registry reg))
        stop-batch (scheduler/run-scheduler! server)
        jetty-srv  (jetty/run-jetty (handler/make-handler server)
                                    {:port listen-port :join? false})]
    (util/closeable
     server
     (fn [_]
       (.stop ^Server jetty-srv)
       (stop-batch)
       (.close ipfs-c)))))
(m/=> start! [:=> [:cat #'Config] :any])
