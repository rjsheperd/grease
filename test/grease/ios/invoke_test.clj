(ns grease.ios.invoke-test
  "Tests for grease.ios.invoke — encoding parsing and dispatch.

  The mock bridge records every msg-send call so we can assert correct
  selector, return-type, and argument forwarding without iOS hardware.

  NOTE: coerce-out for object types (NSString, NSArray, etc.) invokes real
  Foundation FFI (e.g. nsnumber->long calls objc_msgSend directly).  Tests
  in this namespace use only methods whose return type has an identity
  coerce-out (void, id, NSInteger, NSUInteger, Double) to avoid FFI.
  Methods with object return types are tested with a hand-crafted method-spec
  that overrides :return to \"id\"."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.foundation :as foundation]
            [grease.ios.invoke :as invoke]
            [grease.ios.mock-bridge :as mock]
            [grease.ios.naming :as naming]
            [grease.ios.registry :as registry]
            [grease.ios.structs :as structs]
            [grease.ios.types :as types]))

(use-fixtures :once
  (fn [f]
    (structs/init!)
    (naming/init!)
    (types/init!)
    (registry/load-all!)
    (f)))

;; =============================================================================
;; parse-encoding
;; =============================================================================

(deftest ^:parallel parse-encoding-void-test
  (testing "void method with two pointer args"
    (is (= {:ret :void :arg-types [:pointer :pointer]}
           (invoke/parse-encoding "v@:@@")))))

(deftest ^:parallel parse-encoding-pointer-no-args-test
  (testing "pointer return no args"
    (is (= {:ret :pointer :arg-types []}
           (invoke/parse-encoding "@@:")))))

(deftest ^:parallel parse-encoding-int64-arg-test
  (testing "numberWithLongLong: encoding"
    (is (= {:ret :pointer :arg-types [:int64]}
           (invoke/parse-encoding "@@:q")))))

(deftest ^:parallel parse-encoding-uint64-test
  (testing "objectAtIndex: encoding"
    (is (= {:ret :pointer :arg-types [:uint64]}
           (invoke/parse-encoding "@@:Q")))))

(deftest ^:parallel parse-encoding-float64-test
  (testing "doubleValue return type"
    (is (= {:ret :float64 :arg-types []}
           (invoke/parse-encoding "d@:")))))

(deftest ^:parallel parse-encoding-float32-test
  (testing "float return type"
    (is (= {:ret :float32 :arg-types []}
           (invoke/parse-encoding "f@:")))))

(deftest ^:parallel parse-encoding-uint64-return-test
  (testing "count return type"
    (is (= {:ret :uint64 :arg-types []}
           (invoke/parse-encoding "Q@:")))))

;; =============================================================================
;; dispatch! — void return, pointer arg
;; addObject: is safe: void coerce-out (identity), id coerce-in (identity).
;; =============================================================================

(def ^:private fake-obj {:address 0xBEEF})

(deftest dispatch-void-return-test
  (testing "dispatch! sends correct msg-send for addObject: (void, pointer arg)"
    (mock/with-mock
      (let [method (registry/method-spec "NSMutableArray" "addObject:")]
        (invoke/dispatch! fake-obj "addObject:" method [fake-obj])
        (is (= 1 (count (mock/calls))))
        (let [{:keys [ret-type sel args]} (first (mock/calls))]
          (is (= :void ret-type))
          (is (= "addObject:" sel))
          (is (= :pointer (first args))))))))

;; =============================================================================
;; dispatch! — NSUInteger return (identity coerce-out), no args
;; count is safe: NSUInteger coerce-out = identity.
;; =============================================================================

(deftest dispatch-uint64-return-test
  (testing "dispatch! sends correct msg-send for count (uint64 return)"
    (mock/with-responses {"count" 3}
      (let [method (registry/method-spec "NSArray" "count")
            result (invoke/dispatch! fake-obj "count" method [])]
        (is (= 1 (count (mock/calls))))
        (let [{:keys [ret-type sel]} (first (mock/calls))]
          (is (= :uint64 ret-type))
          (is (= "count" sel)))
        ;; NSUInteger coerce-out = identity; 3 passes through unchanged
        (is (= 3 result))))))

;; =============================================================================
;; dispatch! — id return (identity coerce-out), NSUInteger arg
;; objectAtIndex: is safe: id coerce-out = identity.
;; =============================================================================

(deftest dispatch-uint64-arg-id-return-test
  (testing "dispatch! passes uint64 arg for objectAtIndex: and returns id"
    (let [elem {:address 0xC0DE}]
      (mock/with-responses {"objectAtIndex:" elem}
        (let [method (registry/method-spec "NSArray" "objectAtIndex:")
              result (invoke/dispatch! fake-obj "objectAtIndex:" method [2])]
          (let [{:keys [sel args]} (first (mock/calls))]
            (is (= "objectAtIndex:" sel))
            (is (= :uint64 (first args)))
            (is (= 2 (second args))))
          ;; id coerce-out = identity; response passes through unchanged
          (is (= elem result)))))))

;; =============================================================================
;; dispatch! — NSInteger return (identity), no args
;; code on NSError: NSInteger coerce-out = identity.
;; =============================================================================

(deftest dispatch-int64-return-test
  (testing "dispatch! returns NSInteger unchanged (identity coerce-out)"
    (mock/with-responses {"code" 404}
      (let [method (registry/method-spec "NSError" "code")
            result (invoke/dispatch! fake-obj "code" method [])]
        (is (= 1 (count (mock/calls))))
        (is (= "code" (:sel (first (mock/calls)))))
        (is (= 404 result))))))

;; =============================================================================
;; dispatch! — NSInteger arg coerce-in (clojure.core/long)
;; longLongValue: NSInteger return = identity.
;; =============================================================================

(deftest dispatch-int64-return-longlong-test
  (testing "dispatch! returns NSInteger for longLongValue"
    (mock/with-responses {"longLongValue" 42}
      (let [method (registry/method-spec "NSNumber" "longLongValue")
            result (invoke/dispatch! fake-obj "longLongValue" method [])]
        (is (= "longLongValue" (:sel (first (mock/calls)))))
        (is (= 42 result))))))

;; =============================================================================
;; dispatch! — two void pointer args
;; setObject:forKey: is safe: void return, id args = identity coerce-in.
;; =============================================================================

(deftest dispatch-two-pointer-args-test
  (testing "dispatch! sends two pointer args for setObject:forKey:"
    (mock/with-mock
      (let [method (registry/method-spec "NSMutableDictionary" "setObject:forKey:")
            k      {:address 0xAAAA}
            v      {:address 0xBBBB}]
        (invoke/dispatch! fake-obj "setObject:forKey:" method [v k])
        (let [{:keys [sel ret-type args]} (first (mock/calls))]
          (is (= "setObject:forKey:" sel))
          (is (= :void ret-type))
          (is (= [:pointer :pointer] (take-nth 2 args))))))))

;; =============================================================================
;; dispatch-class! — class method with fake id return
;; Override :return to "id" so coerce-out is identity; real dispatch is tested.
;; =============================================================================

(deftest dispatch-class-method-test
  (testing "dispatch-class! resolves class pointer and dispatches"
    (let [fake-arr {:address 0xABCD}
          ;; Override :return to "id" so coerce-out is identity (no FFI needed)
          fake-method (assoc (registry/method-spec "NSMutableArray" "array")
                             :return "id")]
      (mock/with-responses {"array" fake-arr}
        (let [result (invoke/dispatch-class! "NSMutableArray" "array" fake-method [])]
          (is (= 1 (count (mock/calls))))
          (is (= "array" (:sel (first (mock/calls)))))
          (is (= :pointer (:ret-type (first (mock/calls)))))
          (is (= fake-arr result)))))))

;; =============================================================================
;; dispatch! — int64 arg coerce-in (clojure.core/long)
;; numberWithLongLong: — override :return to "id" (real return is NSNumber = FFI).
;; =============================================================================

(deftest dispatch-int64-arg-test
  (testing "dispatch! passes int64 arg for numberWithLongLong:"
    (let [fake-num {:address 0x1234}
          fake-method (assoc (registry/method-spec "NSNumber" "numberWithLongLong:")
                             :return "id")]
      (mock/with-responses {"numberWithLongLong:" fake-num}
        (let [result (invoke/dispatch-class! "NSNumber" "numberWithLongLong:" fake-method [42])]
          (is (= 1 (count (mock/calls))))
          (let [{:keys [sel args]} (first (mock/calls))]
            (is (= "numberWithLongLong:" sel))
            (is (= :int64 (first args)))
            (is (= 42 (second args))))
          (is (= fake-num result)))))))

;; =============================================================================
;; Phase 8.2 — auto-coerce: String arg for id-typed addObject: (real ObjC call)
;;
;; Uses a real NSMutableArray on macOS JVM to verify that dispatch! auto-boxes
;; a Clojure String to an NSString when the arg type is "id".
;; No mock bridge — this makes actual ObjC calls via the macOS Foundation runtime.
;; =============================================================================

(deftest dispatch-auto-coerce-string-arg-test
  (testing "dispatch! auto-boxes String arg via coerce-id-arg for id-typed args"
    ;; Create a real NSMutableArray via foundation and dispatch addObject: "hello"
    ;; without pre-boxing.  Verify via count (NSUInteger return, no :uint64 arg)
    ;; that the element was added — objectAtIndex: takes NSUInteger (:uint64) which
    ;; the macOS JVM FFI does not support as an argument type.
    (let [arr-ptr (foundation/->nsarray [])
          method  (registry/method-spec "NSMutableArray" "addObject:")
          _       (invoke/dispatch! arr-ptr "addObject:" method ["hello"])
          count-m (registry/method-spec "NSArray" "count")
          cnt     (invoke/dispatch! arr-ptr "count" count-m [])]
      (is (= 1 cnt)))))

;; =============================================================================
;; Phase 10.2.4 — struct-return detection
;;
;; Verifies that dispatch! correctly identifies struct-returning methods and
;; that the struct registry is populated after structs/init! is called.
;; The actual stret call path is exercised on-device via test_engine_phase9_10.clj.
;; =============================================================================

(deftest dispatch-struct-return-detection-test
  (testing "structs/known-struct? correctly identifies CLLocationCoordinate2D"
    ;; structs/init! is called in the :once fixture
    (is (true? (structs/known-struct? "CLLocationCoordinate2D")))
    (is (true? (structs/known-struct? "CGPoint")))
    (is (true? (structs/known-struct? "CGRect")))
    (is (false? (structs/known-struct? "NSString")))
    (is (false? (structs/known-struct? "id"))))

  (testing "CoreLocation.edn coordinate method has CLLocationCoordinate2D return type"
    (let [m (registry/method-spec "CLLocation" "coordinate")]
      (is (some? m) "coordinate method exists in registry")
      (is (= "CLLocationCoordinate2D" (:return m)))))

  (testing "CLLocationCoordinate2D struct spec has correct fields"
    (let [spec (structs/struct-for "CLLocationCoordinate2D")]
      (is (= 16 (:size spec)))
      (is (= 2 (count (:fields spec))))
      (is (= "latitude" (:name (first (:fields spec)))))
      (is (= "longitude" (:name (second (:fields spec))))))))
