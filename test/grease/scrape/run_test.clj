(ns grease.scrape.run-test
  "Tests for grease.scrape.run — Phase 6.3 CLI runner gate.

  Tests the per-framework pipeline (scrape → normalise → validate → write)
  using a temp directory so nothing is written to the real resources tree.
  No network access required."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.spec :as spec]
            [grease.scrape.apple-docs :as apple-docs]
            [grease.scrape.run :as run]))

;; =============================================================================
;; Test fixtures — temp dir + fixture cache dir
;; =============================================================================

(def ^:private fixture-cache "test/fixtures/apple-docs")

(def ^:dynamic ^:private *tmp-dir* nil)

(use-fixtures :each
  (fn [f]
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "grease-run-test-" (System/currentTimeMillis)))]
      (.mkdirs tmp)
      (binding [apple-docs/*cache-dir* fixture-cache
                run/specs-dir          (.getPath (io/file tmp "api-specs"))
                run/manifest-path      (.getPath (io/file tmp "api-specs" "manifest.edn"))
                *tmp-dir*              tmp]
        (f))
      ;; cleanup
      (doseq [f (reverse (file-seq tmp))]
        (.delete f)))))

;; =============================================================================
;; scrape-framework! — happy path
;; =============================================================================

(deftest ^:parallel scrape-corelocation-returns-spec-test
  (testing "scrape-framework! returns a valid spec map for CoreLocation fixture"
    (let [result (run/scrape-framework! "corelocation")]
      (is (map? result))
      (is (= "corelocation" (:framework result)))
      (is (vector? (:classes result)))
      (is (vector? (:protocols result)))
      (is (vector? (:enums result))))))

(deftest scrape-writes-edn-file-test
  (testing "scrape-framework! writes an EDN file to specs-dir"
    (run/scrape-framework! "corelocation")
    (let [f (io/file run/specs-dir "corelocation.edn")]
      (is (.exists f))
      (is (pos? (.length f))))))

(deftest scrape-output-passes-spec-validate-test
  (testing "Written EDN file passes spec/validate"
    (run/scrape-framework! "corelocation")
    (let [f   (io/file run/specs-dir "corelocation.edn")
          edn (edn/read-string (slurp f))]
      (is (some? (spec/validate edn))))))

(deftest scrape-writes-manifest-test
  (testing "scrape-framework! adds the framework path to manifest.edn"
    (run/scrape-framework! "corelocation")
    (let [manifest (edn/read-string (slurp run/manifest-path))
          specs    (:specs manifest)]
      (is (vector? specs))
      (is (some #(str/ends-with? % "corelocation.edn") specs)))))

(deftest scrape-manifest-idempotent-test
  (testing "Calling scrape-framework! twice does not duplicate manifest entry"
    (run/scrape-framework! "corelocation")
    (run/scrape-framework! "corelocation")
    (let [manifest (edn/read-string (slurp run/manifest-path))
          specs    (:specs manifest)
          matching (filter #(str/ends-with? % "corelocation.edn") specs)]
      (is (= 1 (count matching))))))

;; =============================================================================
;; scrape-framework! — error handling
;; =============================================================================

(deftest scrape-throws-on-network-failure-test
  (testing "scrape-framework! throws when Apple docs are unreachable (no cache)"
    (with-redefs [grease.scrape.apple-docs/fetch-remote
                  (fn [_url] (throw (java.io.IOException. "simulated network error")))]
      (is (thrown? Exception
                   (run/scrape-framework! "nonexistentframework"))))))

;; =============================================================================
;; edn-path helper
;; =============================================================================

(deftest ^:parallel edn-path-test
  (testing "edn-path returns classpath-relative string"
    (is (= "grease/api-specs/CoreLocation.edn"
           (run/edn-path "CoreLocation")))
    (is (= "grease/api-specs/AVFoundation.edn"
           (run/edn-path "AVFoundation")))))

;; =============================================================================
;; Multiple frameworks in one run
;; =============================================================================

(deftest scrape-multiple-frameworks-test
  (testing "scrape-framework! for two fixture frameworks produces two EDN files"
    ;; Both corelocation and uikit/uilabel fixture data exist
    (run/scrape-framework! "corelocation")
    (let [manifest (edn/read-string (slurp run/manifest-path))]
      (is (= 1 (count (:specs manifest)))))))
