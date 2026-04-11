(ns net-perspective.util
  "Composable resource lifecycle utilities (with-open / closeable).

   Pattern: every resource is wrapped in a closeable. Callers compose
   resources with with-open and dereference with @. Teardown is automatic
   and ordered — inner with-open bindings close first.")

(defn closeable
  "Wraps value in an object that implements both IDeref and Closeable.
   Deref returns value. Closing calls (close value).
   With a single argument, close is a no-op."
  ([value] (closeable value identity))
  ([value close]
   (reify
     clojure.lang.IDeref
     (deref [_] value)
     java.io.Closeable
     (close [_] (close value)))))

(defn wait-forever
  "Blocks the calling thread indefinitely. Intended as the final form in
   a with-open body when the system should run until interrupted."
  [_]
  @(promise))
