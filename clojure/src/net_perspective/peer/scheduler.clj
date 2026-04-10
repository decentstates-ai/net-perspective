(ns net-perspective.peer.scheduler
  "10-minute batch scheduler with cancel-replace semantics."
  (:require [net-perspective.peer.batch :as batch]))

(def batch-interval-ms (* 10 60 1000))

(defn run-scheduler!
  "Starts the batch scheduler. Runs the batch immediately, then every
   10 minutes. Cancel-replace: if the previous batch is still running when
   the next tick fires, it is cancelled before the new one starts.

   Returns a zero-argument stop-fn. Calling stop-fn cancels any running
   batch and stops the scheduler loop."
  [server]
  (let [current-future (atom nil)
        stop?          (atom false)

        run-batch! (fn []
                     (let [prev @current-future]
                       (when (and prev (not (future-done? prev)))
                         (future-cancel prev)))
                     (reset! current-future
                             (future
                               (try (batch/run-batch! server)
                                    (catch Exception e
                                      (println (str "scheduler batch error: " (.getMessage e))))))))

        scheduler (future
                    (run-batch!)
                    (loop []
                      (Thread/sleep batch-interval-ms)
                      (when-not @stop?
                        (run-batch!)
                        (recur))))]

    (fn stop []
      (reset! stop? true)
      (future-cancel scheduler)
      (let [curr @current-future]
        (when (and curr (not (future-done? curr)))
          (future-cancel curr))))))
