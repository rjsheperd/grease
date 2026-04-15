(ns grease.ios.registry-test
  "Tests for grease.ios.registry — in-memory spec registry and retention table.
  All tests run on plain JVM; no iOS hardware required."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.naming :as naming]
            [grease.ios.registry :as registry]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (naming/init!)
    (registry/load-all!)
    (f)))

;; =============================================================================
;; class-spec
;; =============================================================================

(deftest ^:parallel class-spec-nsstring-test
  (testing "class-spec returns a map for NSString"
    (let [cs (registry/class-spec "NSString")]
      (is (map? cs))
      (is (= "NSString" (:name cs))))))

(deftest ^:parallel class-spec-nsmutablearray-test
  (testing "class-spec returns a map for NSMutableArray"
    (let [cs (registry/class-spec "NSMutableArray")]
      (is (map? cs))
      (is (= "NSMutableArray" (:name cs))))))

(deftest ^:parallel class-spec-unknown-test
  (testing "class-spec returns nil for unknown class"
    (is (nil? (registry/class-spec "NSCompletelyMadeUp")))))

(deftest ^:parallel class-spec-all-foundation-classes-test
  (testing "all Foundation classes are indexed"
    (doseq [cls ["NSString" "NSNumber" "NSArray" "NSMutableArray"
                 "NSDictionary" "NSMutableDictionary" "NSURL" "NSError"]]
      (is (some? (registry/class-spec cls))
          (str "Missing from registry: " cls)))))

;; =============================================================================
;; class clj-names
;; =============================================================================

(deftest ^:parallel class-clj-name-test
  (testing "class-spec entries have ::naming/clj-name annotations"
    (is (= "string"           (::naming/clj-name (registry/class-spec "NSString"))))
    (is (= "url"              (::naming/clj-name (registry/class-spec "NSURL"))))
    (is (= "mutable-array"    (::naming/clj-name (registry/class-spec "NSMutableArray"))))))

;; =============================================================================
;; method-spec
;; =============================================================================

(deftest ^:parallel method-spec-instance-test
  (testing "method-spec finds an instance method"
    (let [m (registry/method-spec "NSString" "length")]
      (is (map? m))
      (is (= "length" (:selector m)))
      (is (= "Q@:" (:encoding m))))))

(deftest ^:parallel method-spec-init-test
  (testing "method-spec finds a class factory method from :init"
    (let [m (registry/method-spec "NSNumber" "numberWithLongLong:")]
      (is (map? m))
      (is (= "numberWithLongLong:" (:selector m)))
      (is (= "@@:q" (:encoding m))))))

(deftest ^:parallel method-spec-unknown-test
  (testing "method-spec returns nil for unknown selector"
    (is (nil? (registry/method-spec "NSString" "completelyMadeUp")))))

(deftest ^:parallel method-spec-unknown-class-test
  (testing "method-spec returns nil for unknown class"
    (is (nil? (registry/method-spec "NSFake" "length")))))

;; =============================================================================
;; retain! / release! / retained
;; =============================================================================

(deftest retain-release-test
  (testing "retain! stores pointer, release! removes it"
    (let [ptr {:address 0xDEADBEEF}]
      (registry/retain! :test-key ptr)
      (is (= ptr (registry/retained :test-key)))
      (registry/release! :test-key)
      (is (nil? (registry/retained :test-key))))))

(deftest retain-returns-ptr-test
  (testing "retain! returns the ptr unchanged"
    (let [ptr {:address 0xCAFEBABE}]
      (is (= ptr (registry/retain! :return-test ptr)))
      (registry/release! :return-test))))

(deftest retain-idempotent-test
  (testing "retaining the same key twice overwrites"
    (let [ptr1 {:address 0x1111}
          ptr2 {:address 0x2222}]
      (registry/retain! :idem-key ptr1)
      (registry/retain! :idem-key ptr2)
      (is (= ptr2 (registry/retained :idem-key)))
      (registry/release! :idem-key))))

;; =============================================================================
;; enum-keyword-for
;; =============================================================================

(deftest ^:parallel enum-keyword-foundation-empty-test
  (testing "enum-keyword-for returns nil — Foundation has no enums"
    (is (nil? (registry/enum-keyword-for "NSStringEncoding" 4)))))
