(ns grease.ios.spec-test
  "Tests for grease.ios.spec — EDN spec loader and validator.
  All tests run on plain JVM; no iOS hardware required."
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.spec :as spec]))

;; =============================================================================
;; load-one / manifest
;; =============================================================================

(deftest ^:parallel foundation-loads-test
  (testing "Foundation.edn loads and has top-level keys"
    (let [s (spec/load-one "grease/api-specs/Foundation.edn")]
      (is (map? s))
      (is (= "Foundation" (:framework s)))
      (is (vector? (:classes s)))
      (is (vector? (:enums s)))
      (is (pos? (count (:classes s)))))))

(deftest ^:parallel load-all-returns-foundation-test
  (testing "load-all returns a map with Foundation entry"
    (let [all (spec/load-all)]
      (is (map? all))
      (is (contains? all "Foundation"))
      (is (= "Foundation" (get-in all ["Foundation" :framework]))))))

;; =============================================================================
;; Foundation class coverage
;; =============================================================================

(deftest ^:parallel foundation-classes-test
  (testing "Foundation spec covers expected classes"
    (let [classes (->> (spec/load-one "grease/api-specs/Foundation.edn")
                       :classes
                       (map :name)
                       set)]
      (doseq [expected ["NSString" "NSNumber" "NSArray" "NSMutableArray"
                        "NSDictionary" "NSMutableDictionary" "NSURL" "NSError"]]
        (is (contains? classes expected)
            (str "Missing class: " expected))))))

(deftest ^:parallel foundation-methods-test
  (testing "key selectors are present in the spec"
    (let [spec  (spec/load-one "grease/api-specs/Foundation.edn")
          index (into {}
                      (map (fn [cls]
                             [(:name cls)
                              (set (map :selector
                                        (concat (:init cls) (:methods cls))))])
                           (:classes spec)))]
      ;; NSString
      (is (contains? (index "NSString") "stringWithUTF8String:"))
      (is (contains? (index "NSString") "UTF8String"))
      ;; NSNumber
      (is (contains? (index "NSNumber") "numberWithLongLong:"))
      (is (contains? (index "NSNumber") "numberWithDouble:"))
      (is (contains? (index "NSNumber") "longLongValue"))
      (is (contains? (index "NSNumber") "doubleValue"))
      ;; NSArray / NSMutableArray
      (is (contains? (index "NSArray") "count"))
      (is (contains? (index "NSArray") "objectAtIndex:"))
      (is (contains? (index "NSMutableArray") "array"))
      (is (contains? (index "NSMutableArray") "addObject:"))
      ;; NSDictionary / NSMutableDictionary
      (is (contains? (index "NSDictionary") "allKeys"))
      (is (contains? (index "NSDictionary") "objectForKey:"))
      (is (contains? (index "NSMutableDictionary") "dictionary"))
      (is (contains? (index "NSMutableDictionary") "setObject:forKey:"))
      ;; NSURL
      (is (contains? (index "NSURL") "URLWithString:"))
      (is (contains? (index "NSURL") "absoluteString"))
      ;; NSError
      (is (contains? (index "NSError") "domain"))
      (is (contains? (index "NSError") "code"))
      (is (contains? (index "NSError") "localizedDescription")))))

;; =============================================================================
;; validate — structural checks
;; =============================================================================

(deftest ^:parallel validate-missing-framework-test
  (testing "validate throws when :framework is absent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate {:classes [] :enums []})))))

(deftest ^:parallel validate-missing-classes-test
  (testing "validate throws when :classes is absent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate {:framework "Test" :enums []})))))

(deftest ^:parallel validate-missing-enums-test
  (testing "validate throws when :enums is absent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate {:framework "Test" :classes []})))))

(deftest ^:parallel validate-class-missing-name-test
  (testing "validate throws when a class has no :name"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate {:framework "Test"
                                 :classes   [{:init [] :methods []}]
                                 :enums     []})))))

(deftest ^:parallel validate-method-missing-selector-test
  (testing "validate throws when a method has no :selector"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate {:framework "Test"
                                 :classes   [{:name    "Foo"
                                              :init    []
                                              :methods [{:encoding "v@:" :args []}]}]
                                 :enums     []})))))

(deftest ^:parallel validate-method-missing-encoding-test
  (testing "validate throws when a method has no :encoding"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate {:framework "Test"
                                 :classes   [{:name    "Foo"
                                              :init    []
                                              :methods [{:selector "foo" :args []}]}]
                                 :enums     []})))))

(deftest ^:parallel validate-ok-test
  (testing "validate returns spec unchanged when valid"
    (let [s {:framework "Test"
             :classes   [{:name "Foo" :init [] :methods [{:selector "bar"
                                                          :encoding "v@:"
                                                          :args     []}]}]
             :enums     []}]
      (is (= s (spec/validate s))))))

;; =============================================================================
;; load-one — error paths
;; =============================================================================

(deftest ^:parallel load-one-missing-path-test
  (testing "load-one throws for a non-existent classpath resource"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/load-one "grease/api-specs/DoesNotExist.edn")))))
