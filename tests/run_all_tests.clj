;; tests/run_all_tests.clj — run every test suite in a single JVM.
;;
;; Opens one nREPL connection, loads each suite file in order, collects
;; results, and exits 0 (all pass) or 1 (any failure).
;;
;; Usage:
;;   clj -Sdeps '{"deps" {"nrepl/nrepl" {"mvn/version" "1.3.0"}}}' \
;;       -M tests/run_all_tests.clj
;;
;;   NREPL_HOST=192.168.0.222 clj ... -M tests/run_all_tests.clj
;;
;;   # specific suites only:
;;   clj ... -M tests/run_all_tests.clj hiccup nav
;;
;; Suite names map to files below. Passing no args runs the default set.
;; Pass --all to include the location suite (requires GPS / permission grant).

(load-file "tests/test_runner.clj")

;; ── Suite registry ────────────────────────────────────────────────────────────

(def suite-files
  {"foundation"     "tests/test_foundation.clj"
   "repl"           "tests/test_repl.clj"
   "bluetooth"      "tests/test_bluetooth.clj"
   "blocks"         "tests/test_blocks.clj"
   "uikit"          "tests/test_uikit.clj"
   "engine"         "tests/test_engine.clj"
   "frameworks"     "tests/test_frameworks.clj"
   "hiccup"         "tests/test_engine_hiccup.clj"
   "hiccup-diffing" "tests/test_engine_hiccup_diffing.clj"
   "nav"            "tests/test_engine_nav.clj"
   "location"       "tests/test_location.clj"})

(def default-suites
  ["foundation" "repl" "bluetooth" "blocks" "uikit"
   "engine" "frameworks" "hiccup" "hiccup-diffing" "nav"])

(def all-suites (conj default-suites "location"))

;; ── Argument parsing ──────────────────────────────────────────────────────────

(def cli-args (vec *command-line-args*))

(def suites-to-run
  (cond
    (= cli-args ["--all"])  all-suites
    (seq cli-args)          cli-args
    :else                   default-suites))

;; ── Validate ─────────────────────────────────────────────────────────────────

(doseq [s suites-to-run]
  (when-not (contains? suite-files s)
    (println (str "Unknown suite: " s
                  "  (valid: " (clojure.string/join ", " (sort (keys suite-files))) ")"))
    (System/exit 1)))

;; ── Run ───────────────────────────────────────────────────────────────────────

;; Sentinel var: run-suite checks (resolve 'run-all-suites) to detect composed mode.
(def run-all-suites true)

(connect!)

(def suite-summaries
  (mapv (fn [suite]
          (let [file (get suite-files suite)]
            (println)
            (println (apply str (repeat 72 "━")))
            (println (str "  Suite: " suite "  (" file ")"))
            (println (apply str (repeat 72 "━")))
            (println)
            (flush)
            (reset-results!)
            (load-file file)
            ;; run-suite was already called by load-file; its return value is
            ;; stored in the last expression. Recover it from results directly.
            {:suite  suite
             :passed (count (filter :pass? @results))
             :failed (count (remove :pass? @results))}))
        suites-to-run))

;; ── Final summary ─────────────────────────────────────────────────────────────

(println)
(println (apply str (repeat 72 "━")))
(println (str "  FINAL SUMMARY — " (count suites-to-run) " suites"))
(println (apply str (repeat 72 "━")))
(doseq [{:keys [suite passed failed]} suite-summaries]
  (println (str "  " (if (zero? failed) "PASS" "FAIL")
                "  " suite
                "  (" passed " passed"
                (when (pos? failed) (str ", " failed " FAILED"))
                ")")))
(println)

(let [total-passed (reduce + (map :passed suite-summaries))
      total-failed (reduce + (map :failed suite-summaries))]
  (println (str "  Total: " total-passed " passed, " total-failed " failed"))
  (println (apply str (repeat 72 "━")))
  (System/exit (if (zero? total-failed) 0 1)))
