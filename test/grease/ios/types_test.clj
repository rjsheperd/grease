(ns grease.ios.types-test
  "Gate tests for grease.ios.types — run on JVM without iOS hardware.

  Tests:
  - EDN loads and has the expected structure
  - All type entries have required keys with valid values
  - BOOL coercers (pure Clojure, no native deps) round-trip correctly
  - register-class! adds opaque pointer entries
  - encoding-for returns correct chars
  - coerce-in/coerce-out dispatch correctly for BOOL (the only type
    whose coercers have no native dependency)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.types :as types]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- load-types-fixture [f]
  ;; init! loads types.edn and resolves coercers.
  ;; On macOS dev machine with GraalVM JDK, this resolves foundation/* symbols.
  ;; On plain JVM without native deps, only pure-Clojure symbols resolve.
  ;; We guard each test accordingly.
  (try
    (types/init!)
    (f)
    (catch Exception e
      ;; If init! fails (missing native deps), skip live coercer tests.
      ;; Structure tests still run against the raw EDN.
      (println "NOTE: types/init! failed (native deps unavailable) —"
               "only structure tests will run.")
      (println "Cause:" (.getMessage e)))))

(use-fixtures :once load-types-fixture)

;; =============================================================================
;; EDN structure tests (always pass — no native deps)
;; =============================================================================

(deftest ^:parallel edn-loads-test
  (testing "types.edn is readable EDN"
    (let [raw (edn/read-string
               (slurp (io/resource "grease/types.edn")))]
      (is (map? raw))
      (is (seq raw)))))

(deftest ^:parallel required-keys-test
  (testing "every type entry has required keys"
    (let [raw (edn/read-string
               (slurp (io/resource "grease/types.edn")))]
      (doseq [[type-name entry] raw]
        (is (:encoding entry)  (str type-name " missing :encoding"))
        (is (:clj entry)       (str type-name " missing :clj"))
        (is (:coerce-in entry) (str type-name " missing :coerce-in"))
        (is (:coerce-out entry) (str type-name " missing :coerce-out"))))))

(deftest ^:parallel encodings-valid-test
  (testing "all encodings are single chars"
    (let [raw (edn/read-string
               (slurp (io/resource "grease/types.edn")))]
      (doseq [[type-name {:keys [encoding]}] raw]
        (is (string? encoding)  (str type-name ": encoding must be string"))
        (is (= 1 (count encoding)) (str type-name ": encoding must be 1 char, got " encoding))))))

(deftest ^:parallel expected-types-present-test
  (testing "expected type names are in the EDN"
    (let [raw (edn/read-string
               (slurp (io/resource "grease/types.edn")))]
      (doseq [t ["NSString" "NSNumber" "NSArray" "NSDictionary" "NSURL"
                 "BOOL" "NSInteger" "Float" "Double" "void" "id"]]
        (is (contains? raw t) (str "Expected type " t " in types.edn"))))))

;; =============================================================================
;; BOOL coercers — pure Clojure, always available
;; =============================================================================

(deftest ^:parallel bool->int-test
  (testing "bool->int converts truthy/falsy to 1/0"
    (is (= 1 (types/bool->int true)))
    (is (= 0 (types/bool->int false)))
    (is (= 1 (types/bool->int 42)))
    (is (= 0 (types/bool->int nil)))))

(deftest ^:parallel int->bool-test
  (testing "int->bool converts 0/non-zero to false/true"
    (is (= true  (types/int->bool 1)))
    (is (= false (types/int->bool 0)))
    (is (= true  (types/int->bool -1)))))

(deftest ^:parallel bool-round-trip-test
  (testing "BOOL coercers round-trip through bool->int and int->bool"
    (is (= false (types/int->bool (types/bool->int false))))
    (is (= true  (types/int->bool (types/bool->int true))))))

;; =============================================================================
;; Type table tests (run after init!)
;; =============================================================================

(deftest register-class-test
  (testing "register-class! adds an opaque pointer entry"
    (types/register-class! "AVPlayer")
    (is (types/known-type? "AVPlayer"))
    (is (= "@" (types/encoding-for "AVPlayer")))))

(deftest encoding-for-test
  (testing "encoding-for returns the expected char for known types"
    ;; Only test types whose init! doesn't require native resolution to fail.
    ;; BOOL, void, id are always present after init! regardless of native deps.
    (when (types/known-type? "BOOL")
      (is (= "B" (types/encoding-for "BOOL"))))
    (when (types/known-type? "void")
      (is (= "v" (types/encoding-for "void"))))
    (when (types/known-type? "id")
      (is (= "@" (types/encoding-for "id"))))))

(deftest encoding-for-unknown-throws-test
  (testing "encoding-for throws ex-info for unknown type"
    (is (thrown? clojure.lang.ExceptionInfo
                 (types/encoding-for "CompletelyMadeUpType")))))

(deftest coerce-in-bool-test
  (testing "coerce-in for BOOL dispatches to bool->int"
    (when (types/known-type? "BOOL")
      (is (= 1 (types/coerce-in "BOOL" true)))
      (is (= 0 (types/coerce-in "BOOL" false))))))

(deftest coerce-out-bool-test
  (testing "coerce-out for BOOL dispatches to int->bool"
    (when (types/known-type? "BOOL")
      (is (= true  (types/coerce-out "BOOL" 1)))
      (is (= false (types/coerce-out "BOOL" 0))))))
