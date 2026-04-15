(ns grease.ios.oracle-test
  "Round-trip regression suite — Phase 7.1 gate.

  For every loaded framework spec:
  - Every method :return type resolves in types.edn
  - Every method arg :type resolves in types.edn
  - Every enum value has a ::naming/clj-name keyword
  - Every enum round-trips both ways (kw → raw, raw → kw)
  - Every declared protocol is accessible via registry/protocol-spec
  - Every class is retrievable by name from the registry

  This is the regression net for scraper output: any EDN that passes
  spec/validate must also pass this suite.  All tests run on plain JVM
  (no iOS hardware required)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.api :as api]
            [grease.ios.naming :as naming]
            [grease.ios.registry :as registry]
            [grease.ios.types :as types]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (api/load!)
    (f)))

;; =============================================================================
;; Method return-type resolution
;; =============================================================================

(deftest all-method-return-types-resolve-test
  (testing "Every non-:unsupported method :return type is registered in types.edn"
    (doseq [[fw-key spec] (registry/all-specs)]
      (testing (name fw-key)
        (doseq [cls (:classes spec)]
          (testing (:name cls)
            (doseq [m (concat (:init cls) (:methods cls))]
              (when-not (:unsupported m)
                (is (types/known-type? (:return m))
                    (str (:selector m) " :return=" (:return m)))))))))))

;; =============================================================================
;; Method arg-type resolution
;; =============================================================================

(deftest all-method-arg-types-resolve-test
  (testing "Every non-:unsupported method arg :type is registered in types.edn"
    (doseq [[fw-key spec] (registry/all-specs)]
      (testing (name fw-key)
        (doseq [cls (:classes spec)]
          (testing (:name cls)
            (doseq [m (concat (:init cls) (:methods cls))]
              (when-not (:unsupported m)
                (doseq [arg (:args m)]
                  (is (types/known-type? (:type arg))
                      (str (:selector m) " arg:" (:name arg) " type=" (:type arg))))))))))))

;; =============================================================================
;; Enum completeness
;; =============================================================================

(deftest all-enum-values-have-clj-name-test
  (testing "Every enum value has a ::naming/clj-name keyword after transform"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [e (:enums spec)]
        (testing (:name e)
          (doseq [v (:values e)]
            (is (keyword? (::naming/clj-name v))
                (str "Missing clj-name on " (:name v)))))))))

;; =============================================================================
;; Enum round-trips
;; =============================================================================

(deftest all-enums-kw->raw-test
  (testing "enum-raw-for returns the correct raw integer for every clj keyword"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [e (:enums spec)]
        (testing (:name e)
          (doseq [v (:values e)]
            (let [kw  (::naming/clj-name v)
                  raw (:raw v)]
              (is (= raw (registry/enum-raw-for (:name e) kw))
                  (str kw " should map to raw=" raw)))))))))

(deftest all-enums-raw->kw-test
  (testing "enum-keyword-for returns the correct keyword for every raw integer"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [e (:enums spec)]
        (testing (:name e)
          (doseq [v (:values e)]
            (let [kw  (::naming/clj-name v)
                  raw (:raw v)]
              (is (= kw (registry/enum-keyword-for (:name e) raw))
                  (str "raw=" raw " should map to " kw)))))))))

;; =============================================================================
;; Protocol accessibility
;; =============================================================================

(deftest all-protocols-accessible-test
  (testing "Every spec-declared protocol is accessible via registry/protocol-spec"
    (doseq [[fw-key spec] (registry/all-specs)]
      (testing (name fw-key)
        (doseq [proto (:protocols spec)]
          (testing (:name proto)
            (let [found (registry/protocol-spec (:name proto))]
              (is (some? found)
                  (str "Protocol not in registry: " (:name proto))))))))))

(deftest all-protocols-name-matches-test
  (testing "Retrieved protocol map has :name matching the lookup key"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [proto (:protocols spec)]
        (let [found (registry/protocol-spec (:name proto))]
          (when found
            (is (= (:name proto) (:name found)))))))))

(deftest all-protocol-methods-have-selectors-test
  (testing "Every protocol method has a non-empty :selector string"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [proto (:protocols spec)]
        (testing (:name proto)
          (doseq [m (:methods proto)]
            (is (and (string? (:selector m))
                     (seq (:selector m)))
                (str "Bad selector in " (:name proto)))))))))

;; =============================================================================
;; Class registry completeness
;; =============================================================================

(deftest all-classes-in-registry-test
  (testing "Every spec class is retrievable by name from the registry"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [cls (:classes spec)]
        (is (some? (registry/class-spec (:name cls)))
            (str "Class not found in registry: " (:name cls)))))))

(deftest all-classes-have-required-keys-test
  (testing "Every class spec has :name :superclass :init :methods :properties"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [cls (:classes spec)]
        (testing (:name cls)
          (is (string? (:name cls)))
          (is (vector? (:init cls)))
          (is (vector? (:methods cls)))
          (is (vector? (:properties cls))))))))

;; =============================================================================
;; Method-spec selector lookup
;; =============================================================================

(deftest all-methods-findable-by-selector-test
  (testing "Every method is findable via registry/method-spec by selector"
    (doseq [[_ spec] (registry/all-specs)]
      (doseq [cls (:classes spec)]
        (testing (:name cls)
          (doseq [m (concat (:init cls) (:methods cls))]
            (let [found (registry/method-spec (:name cls) (:selector m))]
              (is (some? found)
                  (str "method-spec returned nil for "
                       (:name cls) "/" (:selector m))))))))))
