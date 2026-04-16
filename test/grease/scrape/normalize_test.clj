(ns grease.scrape.normalize-test
  "Tests for grease.scrape.normalize — Phase 6.2 gate.

  All tests run against inline sample data or fixture JSON files.
  No network access required."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.scrape.apple-docs :as apple-docs]
            [grease.scrape.normalize :as normalize]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private fixture-dir "test/fixtures/apple-docs")

(use-fixtures :each
  (fn [f]
    (binding [apple-docs/*cache-dir* fixture-dir]
      (f))))

;; =============================================================================
;; build-encoding
;; =============================================================================

(deftest ^:parallel build-encoding-void-no-args-test
  (testing "void return, no args → v@:"
    (is (= "v@:" (normalize/build-encoding "void" [])))))

(deftest ^:parallel build-encoding-id-return-test
  (testing "id return, no args → @@:"
    (is (= "@@:" (normalize/build-encoding "id" [])))))

(deftest ^:parallel build-encoding-void-one-id-arg-test
  (testing "void return, one id arg → v@:@"
    (is (= "v@:@" (normalize/build-encoding "void" ["id"])))))

(deftest ^:parallel build-encoding-void-two-id-args-test
  (testing "void return, two id args → v@:@@"
    (is (= "v@:@@" (normalize/build-encoding "void" ["id" "id"])))))

(deftest ^:parallel build-encoding-nsinteger-return-test
  (testing "NSInteger return → q@:"
    (is (= "q@:" (normalize/build-encoding "NSInteger" [])))))

(deftest ^:parallel build-encoding-bool-arg-test
  (testing "void return with BOOL arg → v@:B"
    (is (= "v@:B" (normalize/build-encoding "void" ["BOOL"])))))

(deftest ^:parallel build-encoding-double-arg-test
  (testing "Double arg encoding → v@:d"
    (is (= "v@:d" (normalize/build-encoding "void" ["CGFloat"])))))

(deftest ^:parallel build-encoding-instancetype-test
  (testing "instancetype maps to @ (same as id)"
    (is (= "@@:" (normalize/build-encoding "instancetype" [])))))

;; =============================================================================
;; normalize-method-ref — zero-arg instance method
;; =============================================================================

(def ^:private start-updating-location-ref
  {:title     "startUpdatingLocation"
   :role      "symbol"
   :kind      "symbol"
   :fragments [{:kind "text"           :text "- ("}
               {:kind "typeIdentifier" :text "void"}
               {:kind "text"           :text ") "}
               {:kind "identifier"     :text "startUpdatingLocation"}]})

(deftest ^:parallel normalize-zero-arg-instance-method-test
  (testing "normalizes startUpdatingLocation → void return, no args"
    (let [m (normalize/normalize-method-ref start-updating-location-ref)]
      (is (= "startUpdatingLocation" (:selector m)))
      (is (= "void" (:return m)))
      (is (= "v@:" (:encoding m)))
      (is (= [] (:args m))))))

;; =============================================================================
;; normalize-method-ref — one-arg instance method (NSString)
;; =============================================================================

(def ^:private set-text-ref
  {:title     "setText:"
   :role      "symbol"
   :kind      "symbol"
   :fragments [{:kind "text"           :text "- ("}
               {:kind "typeIdentifier" :text "void"}
               {:kind "text"           :text ") "}
               {:kind "identifier"     :text "setText:"}
               {:kind "text"           :text "("}
               {:kind "typeIdentifier" :text "NSString"
                :preciseIdentifier "c:objc(cs)NSString"}
               {:kind "text"           :text " *) "}
               {:kind "internalParam"  :text "text"}]})

(deftest ^:parallel normalize-one-arg-nsstring-test
  (testing "normalizes setText: → void return, one NSString arg"
    (let [m (normalize/normalize-method-ref set-text-ref)]
      (is (= "setText:" (:selector m)))
      (is (= "void" (:return m)))
      (is (= "v@:@" (:encoding m)))
      (is (= 1 (count (:args m))))
      (is (= "NSString" (:type (first (:args m)))))
      (is (= "text" (:name (first (:args m))))))))

;; =============================================================================
;; normalize-method-ref — class method (instancetype return)
;; =============================================================================

(def ^:private new-ref
  {:title     "new"
   :role      "symbol"
   :kind      "symbol"
   :fragments [{:kind "text"           :text "+ ("}
               {:kind "typeIdentifier" :text "instancetype"}
               {:kind "text"           :text ")"}
               {:kind "identifier"     :text "new"}]})

(deftest ^:parallel normalize-class-method-test
  (testing "normalizes +new → id return (instancetype maps to id), no args"
    (let [m (normalize/normalize-method-ref new-ref)]
      (is (= "new" (:selector m)))
      (is (= "id" (:return m)))
      (is (= "@@:" (:encoding m)))
      (is (= [] (:args m))))))

;; =============================================================================
;; normalize-method-ref — NSString return
;; =============================================================================

(def ^:private text-getter-ref
  {:title     "text"
   :role      "symbol"
   :kind      "symbol"
   :fragments [{:kind "text"           :text "- ("}
               {:kind "typeIdentifier" :text "NSString"
                :preciseIdentifier "c:objc(cs)NSString"}
               {:kind "text"           :text " *) "}
               {:kind "identifier"     :text "text"}]})

(deftest ^:parallel normalize-nsstring-return-test
  (testing "normalizes text getter → NSString return"
    (let [m (normalize/normalize-method-ref text-getter-ref)]
      (is (= "text" (:selector m)))
      (is (= "NSString" (:return m)))
      (is (= "@@:" (:encoding m)))
      (is (= [] (:args m))))))

;; =============================================================================
;; normalize-method-ref — fallback when no fragments
;; =============================================================================

(deftest ^:parallel normalize-no-fragments-fallback-test
  (testing "normalize-method-ref with no fragments defaults to id/@"
    (let [m (normalize/normalize-method-ref {:title "someMethod" :role "symbol"})]
      (is (= "someMethod" (:selector m)))
      (is (= "id" (:return m)))
      (is (= "@@:" (:encoding m)))
      (is (= [] (:args m))))))

;; =============================================================================
;; normalize-class-doc — fixture-based
;; =============================================================================

(deftest normalize-class-doc-structure-test
  (testing "normalize-class-doc produces required class spec keys"
    (let [doc (apple-docs/class-doc "uikit" "UILabel")
          cs  (normalize/normalize-class-doc doc "UILabel")]
      (is (= "UILabel" (:name cs)))
      (is (= "NSObject" (:superclass cs)))
      (is (vector? (:init cs)))
      (is (vector? (:methods cs)))
      (is (vector? (:properties cs))))))

(deftest normalize-class-doc-init-vs-methods-test
  (testing "class methods (+) go to :init; instance methods (-) go to :methods"
    (let [doc (apple-docs/class-doc "uikit" "UILabel")
          cs  (normalize/normalize-class-doc doc "UILabel")]
      ;; +new is a class method → :init
      (is (some #(= "new" (:selector %)) (:init cs)))
      ;; -text and -setText: are instance methods → :methods
      (is (some #(= "text" (:selector %)) (:methods cs)))
      (is (some #(= "setText:" (:selector %)) (:methods cs))))))

(deftest normalize-class-doc-settext-encoding-test
  (testing "setText: encoding is v@:@ (void + one NSString arg)"
    (let [doc (apple-docs/class-doc "uikit" "UILabel")
          cs  (normalize/normalize-class-doc doc "UILabel")
          m   (first (filter #(= "setText:" (:selector %)) (:methods cs)))]
      (is (= "v@:@" (:encoding m)))
      (is (= "void" (:return m)))
      (is (= "NSString" (:type (first (:args m))))))))

(deftest normalize-class-doc-excludes-self-ref-test
  (testing "The class's own self-reference is excluded from init/methods"
    (let [doc (apple-docs/class-doc "uikit" "UILabel")
          cs  (normalize/normalize-class-doc doc "UILabel")
          all-sels (set (map :selector (concat (:init cs) (:methods cs))))]
      ;; "UILabel" (self-ref with @interface fragments) must not appear as a selector
      (is (not (contains? all-sels "UILabel"))))))

;; =============================================================================
;; framework->edn — CoreLocation fixture
;; =============================================================================

(deftest framework->edn-structure-test
  (testing "framework->edn produces required top-level spec keys"
    (let [edn (normalize/framework->edn "corelocation")]
      (is (string? (:framework edn)))
      (is (vector? (:classes edn)))
      (is (vector? (:protocols edn)))
      (is (vector? (:enums edn)))
      (is (vector? (:constants edn))))))

(deftest framework->edn-classes-test
  (testing "framework->edn includes CLLocationManager class"
    (let [edn     (normalize/framework->edn "corelocation")
          cls-names (set (map :name (:classes edn)))]
      (is (contains? cls-names "CLLocationManager")))))

(deftest framework->edn-cllocationmanager-methods-test
  (testing "CLLocationManager has startUpdatingLocation in :methods"
    (let [edn (normalize/framework->edn "corelocation")
          cls (first (filter #(= "CLLocationManager" (:name %)) (:classes edn)))
          sels (set (map :selector (:methods cls)))]
      (is (contains? sels "startUpdatingLocation"))
      (is (contains? sels "requestWhenInUseAuthorization"))
      (is (contains? sels "stopUpdatingLocation")))))

;; =============================================================================
;; Phase 10.3.2 — struct-aware encoding in build-encoding
;; =============================================================================

(deftest ^:parallel build-encoding-cgpoint-return-test
  (testing "CGPoint return emits compound struct encoding"
    (is (= "{CGPoint=dd}@:" (normalize/build-encoding "CGPoint" [])))))

(deftest ^:parallel build-encoding-cgsize-return-test
  (testing "CGSize return emits compound struct encoding"
    (is (= "{CGSize=dd}@:" (normalize/build-encoding "CGSize" [])))))

(deftest ^:parallel build-encoding-cgrect-return-test
  (testing "CGRect return emits nested compound struct encoding"
    (is (= "{CGRect={CGPoint=dd}{CGSize=dd}}@:" (normalize/build-encoding "CGRect" [])))))

(deftest ^:parallel build-encoding-cllocationcoordinate2d-return-test
  (testing "CLLocationCoordinate2D return emits compound struct encoding"
    (is (= "{CLLocationCoordinate2D=dd}@:" (normalize/build-encoding "CLLocationCoordinate2D" [])))))

(deftest ^:parallel build-encoding-cmtime-return-test
  (testing "CMTime return emits correct encoding for mixed primitive fields"
    (is (= "{CMTime=qiIq}@:" (normalize/build-encoding "CMTime" [])))))

(deftest ^:parallel build-encoding-cgpoint-arg-test
  (testing "CGPoint as an argument emits compound struct encoding in args"
    (is (= "v@:{CGPoint=dd}" (normalize/build-encoding "void" ["CGPoint"])))))

;; =============================================================================
;; Phase 10.3.2 — normalize-method-ref with struct return
;; =============================================================================

(def ^:private coordinate-ref
  {:title     "coordinate"
   :role      "symbol"
   :kind      "symbol"
   :fragments [{:kind "text"           :text "- ("}
               {:kind "typeIdentifier" :text "CLLocationCoordinate2D"}
               {:kind "text"           :text ") "}
               {:kind "identifier"     :text "coordinate"}]})

(deftest ^:parallel normalize-struct-return-method-test
  (testing "normalize-method-ref with CLLocationCoordinate2D return"
    (let [m (normalize/normalize-method-ref coordinate-ref)]
      (is (= "coordinate" (:selector m)))
      (is (= "CLLocationCoordinate2D" (:return m)))
      (is (= "{CLLocationCoordinate2D=dd}@:" (:encoding m)))
      (is (= [] (:args m))))))
