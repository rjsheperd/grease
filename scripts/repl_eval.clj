;; repl_eval.clj — evaluate a Clojure form against the device nREPL.
;;
;; Usage (from repo root):
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M scripts/repl_eval.clj '(+ 1 2)'
;;
;; Or via the scripts/repl-eval wrapper.

(require '[nrepl.core :as nrepl])

(let [args    *command-line-args*
      code    (first args)
      host    (or (System/getenv "NREPL_HOST") "192.168.0.111")
      port    (Integer/parseInt (or (System/getenv "NREPL_PORT") "23456"))]
  (when-not code
    (println "Usage: repl_eval.clj '<clojure-form>'")
    (System/exit 1))
  (with-open [conn (nrepl/connect :host host :port port)]
    (let [client  (nrepl/client conn 30000)
          session (nrepl/client-session client)
          msgs    (nrepl/message session {:op "eval" :code code})]
      (doseq [msg msgs]
        (when-let [o (:out msg)]   (print o))
        (when-let [v (:value msg)] (println v))
        (when-let [e (:err msg)]   (binding [*out* *err*] (print e)))
        (when-let [ex (:ex msg)]   (binding [*out* *err*] (println "exception:" ex)))))))
