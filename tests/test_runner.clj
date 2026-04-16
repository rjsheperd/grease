;; tests/test_runner.clj — shared nREPL test infrastructure.
;;
;; Load-file'd at the top of every individual test script:
;;   (load-file "tests/test_runner.clj")
;;
;; Provides:
;;   *host*, *port*           — dynamic vars for connection settings
;;   connect!                 — opens a nREPL connection, stores in *conn*
;;   eval!                    — evaluates code, returns {:value :err :ok?}
;;   check                    — run a single labelled assertion
;;   run-suite                — run a named seq of checks, print summary, exit

(require '[nrepl.core :as nrepl])

(def ^:dynamic *host* (or (System/getenv "NREPL_HOST") "192.168.0.111"))
(def ^:dynamic *port* (Integer/parseInt (or (System/getenv "NREPL_PORT") "23456")))

(def ^:private state (atom {:conn nil :client nil :session nil}))

(defn connect!
  "Opens a persistent nREPL connection with a 120-second client timeout.
  Subsequent eval! calls reuse it."
  ([] (connect! *host* *port*))
  ([host port]
   (let [conn    (nrepl/connect :host host :port port)
         client  (nrepl/client conn 120000)
         session (nrepl/client-session client)]
     (swap! state assoc :conn conn :client client :session session)
     (println (str "Connected to " host ":" port "\n")))))

(defn eval!
  "Evaluates code on the device via the open session.
  Returns {:ok? bool :value last-value-string :out stdout :err error-string}.
  The :timeout option is accepted for API compatibility but the client-wide
  120s timeout from connect! already covers all realistic cases."
  ([code] (eval! code {}))
  ([code _opts]
   (let [session (:session @state)
         msgs    (nrepl/message session {:op "eval" :code code})]
     (reduce
       (fn [acc msg]
         (cond-> acc
           (:value msg) (assoc :value (:value msg))
           (:out   msg) (update :out str (:out msg))
           (:err   msg) (-> (assoc :err (:err msg)) (assoc :ok? false))
           (:ex    msg) (-> (assoc :ex  (:ex  msg)) (assoc :ok? false))))
       {:ok? true :value nil :out "" :err nil}
       msgs))))

(def ^:private results (atom []))

(defn check
  "Runs one labelled assertion against the device REPL.
  pred-or-expected: a fn (applied to result map) or a string (matched against :value).
  Returns true if the assertion passes."
  [label code pred-or-expected & {:keys [timeout] :or {timeout 30000}}]
  (let [result (eval! code {:timeout timeout})
        pass?  (if (fn? pred-or-expected)
                 (pred-or-expected result)
                 (= pred-or-expected (:value result)))]
    (swap! results conj {:label label :pass? pass? :result result})
    (let [tag (if pass? "OK  " "FAIL")]
      (println (str "[" tag "] " label))
      (when-let [v (:value result)] (println (str "       => " (subs v 0 (min 200 (count v))))))
      (when-let [e (:err result)]   (println (str "       !! " (subs e 0 (min 400 (count e)))))))
    (flush)
    pass?))

(defn run-suite
  "Prints a summary for the current result set.  Exits with code 1 if any failed."
  [suite-name]
  (let [all     @results
        passed  (filter :pass? all)
        failed  (remove :pass? all)]
    (println)
    (println (apply str (repeat 60 "=")))
    (doseq [{:keys [label pass?]} all]
      (println (str "  " (if pass? "PASS" "FAIL") "  " label)))
    (println)
    (if (empty? failed)
      (println (str "ALL TESTS PASSED — " suite-name))
      (do
        (println (str (count failed) "/" (count all) " TESTS FAILED — " suite-name))
        (System/exit 1)))))
