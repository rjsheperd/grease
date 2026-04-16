;; tests/test_frameworks.clj — ObjC class-accessibility smoke test for all frameworks.
;;
;; Reads class names from EDN specs on disk via clojure.edn, then sends a single
;; batched eval to the device to probe every class with get-objc-class.
;; Reports per-framework found/total and a final summary.
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_frameworks.clj [framework-filter]
;;
;; Optional argument: substring filter for framework name (case-sensitive).

(load-file "tests/test_runner.clj")

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; ─── Parse specs from disk ──────────────────────────────────────────────────

(def filter-arg (first *command-line-args*))

(defn load-manifest []
  (edn/read-string (slurp "resources/grease/api-specs/manifest.edn")))

(defn load-spec [rel-path]
  (let [f (io/file "resources" rel-path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn objc-class-name? [s]
  (and (string? s) (seq s) (Character/isUpperCase (first s)) (not (str/includes? s " "))))

(defn spec->classes [spec]
  (->> (:classes spec)
       (map :name)
       (filter objc-class-name?)
       distinct
       vec))

(println "Reading specs from disk…")
(def frameworks
  (->> (:specs (load-manifest))
       (keep (fn [rel-path]
               (when-let [spec (load-spec rel-path)]
                 (let [fw-name (:framework spec)
                       classes (spec->classes spec)]
                   (when (and fw-name
                              (seq classes)
                              (or (nil? filter-arg)
                                  (str/includes? fw-name filter-arg)))
                     [fw-name classes])))))
       (into (sorted-map))))

(println (str "Loaded " (count frameworks) " frameworks, "
              (reduce + (map (comp count second) frameworks)) " class names.\n"))

(when (empty? frameworks)
  (println "No frameworks matched — check filter argument or manifest path.")
  (System/exit 1))

;; ─── Connect ────────────────────────────────────────────────────────────────

(connect!)

;; ─── Probe one framework at a time (live progress) ─────────────────────────

(defn clj-str-vec [names]
  (str "[" (str/join " " (map pr-str names)) "]"))

(defn probe-framework [fw classes]
  (let [code (str "(let [classes " (clj-str-vec classes) "\n"
                  "      results (for [cls classes]\n"
                  "                [cls (try (boolean (com.phronemophobic.grease/get-objc-class cls))\n"
                  "                          (catch Exception _ false))])]\n"
                  "  {:total   (count results)\n"
                  "   :found   (count (filter second results))\n"
                  "   :missing (mapv first (remove second results))})")
        {:keys [ok? value err]} (eval! code)]
    (if ok?
      (read-string value)
      (do (println (str "  ERROR: " err)) {:total (count classes) :found 0 :missing classes}))))

(def fw-list (vec frameworks))
(def n-total (count fw-list))

(println (str "Probing " n-total " frameworks (one eval per framework)…\n"))

(def raw
  (into (sorted-map)
    (map-indexed
      (fn [i [fw classes]]
        (let [result (probe-framework fw classes)
              {:keys [total found]} result
              status (cond (zero? total) "EMPTY"
                           (zero? found) "MISS "
                           (< found total) "PART "
                           :else "PASS ")]
          (println (format "  [%2d/%2d] %-30s %3d/%-3d  %s"
                           (inc i) n-total fw found total status))
          (flush)
          [fw result]))
      fw-list)))

(println)
(println (apply str (repeat 72 "=")))
(println (format "  %-32s %6s %6s   STATUS" "FRAMEWORK" "FOUND" "TOTAL"))
(println (apply str (repeat 72 "=")))

(def passed   (atom []))
(def partial  (atom []))
(def missing  (atom []))

(doseq [[fw {:keys [total found]}] (sort-by first raw)]
  (let [status (cond
                 (zero? total)      (do (swap! missing conj fw) "MISS  ")
                 (zero? found)      (do (swap! missing conj fw) "MISS  ")
                 (< found total)    (do (swap! partial conj fw) "PART  ")
                 :else              (do (swap! passed  conj fw) "PASS  "))]
    (println (format "  %-32s %6d/%-6d  %s" fw found total status))))

(println (apply str (repeat 72 "=")))
(println)
(println (format "  PASS    : %3d  (all classes found)"       (count @passed)))
(println (format "  PARTIAL : %3d  (≥1 class found; some missing)" (count @partial)))
(println (format "  MISSING : %3d  (zero classes accessible)" (count @missing)))
(println (format "  TOTAL   : %3d  frameworks probed"         (count raw)))
(println)

(when (seq @missing)
  (println "Inaccessible frameworks:")
  (doseq [fw @missing] (println (str "  • " fw)))
  (println))

(let [accessible (+ (count @passed) (count @partial))]
  (if (= accessible (count raw))
    (println "RESULT: ALL FRAMEWORKS ACCESSIBLE")
    (do
      (println (str "RESULT: " accessible "/" (count raw) " frameworks accessible."))
      (when (zero? accessible)
        (System/exit 1)))))
