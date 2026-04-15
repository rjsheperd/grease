(ns grease.ios.uikit-test
  "Tests for UIKit.edn spec — Phase 4.1 gate.

  Verifies that the spec loads correctly into the registry and that
  all key classes, methods, and enums are accessible.
  All tests run on plain JVM (no iOS hardware required).

  Note: methods returning NSString or NSArray (text, subviews) have
  coerce-out functions that call Foundation FFI — these are not safe
  to dispatch in JVM tests.  Tests use void/id/Double return methods."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.api :as api]
            [grease.ios.foundation]
            [grease.ios.mock-bridge :as mock]
            [grease.ios.registry :as registry]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (api/load!)
    (f)))

;; =============================================================================
;; Spec loading — classes present
;; =============================================================================

(deftest ^:parallel uiapplication-spec-test
  (testing "UIApplication class spec loads"
    (let [cs (registry/class-spec "UIApplication")]
      (is (some? cs))
      (is (= "UIApplication" (:name cs)))
      (is (some #(= "sharedApplication" (:selector %)) (:init cs)))
      (is (some #(= "keyWindow" (:selector %)) (:methods cs))))))

(deftest ^:parallel uiview-spec-test
  (testing "UIView class spec loads with all instance methods"
    (let [cs (registry/class-spec "UIView")]
      (is (some? cs))
      (let [sels (set (map :selector (:methods cs)))]
        (is (contains? sels "subviews"))
        (is (contains? sels "addSubview:"))
        (is (contains? sels "removeFromSuperview"))
        (is (contains? sels "setBackgroundColor:"))))))

(deftest ^:parallel uilabel-spec-test
  (testing "UILabel class spec loads with init + all styling methods"
    (let [cs (registry/class-spec "UILabel")]
      (is (some? cs))
      (is (some #(= "new" (:selector %)) (:init cs)))
      (let [sels (set (map :selector (:methods cs)))]
        (is (contains? sels "text"))
        (is (contains? sels "setText:"))
        (is (contains? sels "setTextColor:"))
        (is (contains? sels "font"))
        (is (contains? sels "setFont:"))
        (is (contains? sels "setTextAlignment:"))))))

(deftest ^:parallel uifont-spec-test
  (testing "UIFont class spec loads with factory methods"
    (let [cs (registry/class-spec "UIFont")]
      (is (some? cs))
      (is (some #(= "fontWithName:size:" (:selector %)) (:init cs)))
      (is (some #(= "systemFontOfSize:" (:selector %)) (:init cs)))
      (is (some #(= "fontName" (:selector %)) (:methods cs)))
      (is (some #(= "pointSize" (:selector %)) (:methods cs))))))

(deftest ^:parallel uicolor-spec-test
  (testing "UIColor class spec loads with color factory methods"
    (let [cs (registry/class-spec "UIColor")]
      (is (some? cs))
      (let [init-sels (set (map :selector (:init cs)))]
        (is (contains? init-sels "colorWithRed:green:blue:alpha:"))
        (is (contains? init-sels "whiteColor"))
        (is (contains? init-sels "blackColor"))))))

;; =============================================================================
;; Method spec details
;; =============================================================================

(deftest ^:parallel settext-encoding-test
  (testing "setText: has v@:@ encoding and NSString arg"
    (let [m (registry/method-spec "UILabel" "setText:")]
      (is (some? m))
      (is (= "v@:@" (:encoding m)))
      (is (= "NSString" (:type (first (:args m)))))
      (is (= "void" (:return m))))))

(deftest ^:parallel settextalignment-encoding-test
  (testing "setTextAlignment: takes NSInteger arg"
    (let [m (registry/method-spec "UILabel" "setTextAlignment:")]
      (is (some? m))
      (is (= "v@:q" (:encoding m)))
      (is (= "NSInteger" (:type (first (:args m))))))))

(deftest ^:parallel fontwithnamesize-encoding-test
  (testing "fontWithName:size: has @@:@d encoding and Double fontSize arg"
    (let [m (registry/method-spec "UIFont" "fontWithName:size:")]
      (is (some? m))
      (is (= "@@:@d" (:encoding m)))
      (is (= "NSString" (:type (first (:args m)))))
      (is (= "Double" (:type (second (:args m))))))))

(deftest ^:parallel colorwithrgba-encoding-test
  (testing "colorWithRed:green:blue:alpha: has @@:dddd encoding"
    (let [m (registry/method-spec "UIColor" "colorWithRed:green:blue:alpha:")]
      (is (some? m))
      (is (= "@@:dddd" (:encoding m)))
      (is (= 4 (count (:args m)))))))

;; =============================================================================
;; NSTextAlignment enum
;; =============================================================================

(deftest ^:parallel nstextalignment-enum-test
  (testing "NSTextAlignment enum values are accessible"
    (is (= 0 (registry/enum-raw-for "NSTextAlignment" :ns-text-alignment-left)))
    (is (= 1 (registry/enum-raw-for "NSTextAlignment" :ns-text-alignment-center)))
    (is (= 2 (registry/enum-raw-for "NSTextAlignment" :ns-text-alignment-right)))
    (is (= 3 (registry/enum-raw-for "NSTextAlignment" :ns-text-alignment-justified)))
    (is (= 4 (registry/enum-raw-for "NSTextAlignment" :ns-text-alignment-natural)))))

(deftest ^:parallel nstextalignment-via-api-test
  (testing "api/enum returns raw int for NSTextAlignment"
    (is (= 1 (api/enum "NSTextAlignment" :ns-text-alignment-center)))))

;; =============================================================================
;; api/call dispatch (void and id returns — JVM-safe)
;; =============================================================================

(deftest addsubview-dispatch-test
  (testing "api/call addSubview: dispatches correctly"
    (mock/with-responses {"addSubview:" nil}
      (let [view  (api/wrap "UIView" {:address 0xAAAA})
            child {:address 0xBBBB}]
        (api/call view "addSubview:" child)
        (is (= 1 (count (mock/calls))))
        (is (= "addSubview:" (:sel (first (mock/calls)))))))))

(deftest removefromsuperview-dispatch-test
  (testing "api/call removeFromSuperview dispatches correctly"
    (mock/with-responses {"removeFromSuperview" nil}
      (let [view (api/wrap "UIView" {:address 0xCCCC})]
        (api/call view "removeFromSuperview")
        (is (= "removeFromSuperview" (:sel (first (mock/calls)))))))))

(deftest settextalignment-dispatch-test
  (testing "api/call setTextAlignment: coerces NSInteger arg"
    (mock/with-responses {"setTextAlignment:" nil}
      (let [label (api/wrap "UILabel" {:address 0xDDDD})]
        ;; NSTextAlignment center = 1, passed as long
        (api/call label "setTextAlignment:" 1)
        (is (= "setTextAlignment:" (:sel (first (mock/calls)))))
        ;; Args: [:int64 1] (NSInteger → :int64 via coerce-in)
        (is (= :int64 (first (:args (first (mock/calls))))))
        (is (= 1 (second (:args (first (mock/calls))))))))))

(deftest pointsize-return-double-test
  (testing "api/call pointSize returns Double (JVM-safe coerce-out)"
    (mock/with-responses {"pointSize" 14.0}
      (let [font (api/wrap "UIFont" {:address 0xEEEE})]
        (is (= 14.0 (api/call font "pointSize")))))))
