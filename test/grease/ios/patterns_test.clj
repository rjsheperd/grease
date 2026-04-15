(ns grease.ios.patterns-test
  "Tests for grease.ios.patterns — delegate proxy pattern.

  All tests run on plain JVM with mock bridge.
  [[grease.ios.patterns/create-delegate-class!]] is redefined to avoid
  native FFI calls (class registration requires the ObjC runtime)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.api :as api]
            [grease.ios.foundation]
            [grease.ios.mock-bridge :as mock]
            [grease.ios.patterns :as patterns]
            [grease.ios.registry :as registry]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (api/load!)       ; loads naming, types, all specs (Foundation + CoreLocation)
    (f)))

;; =============================================================================
;; wrap-arg — passthrough for non-pattern args
;; =============================================================================

(deftest ^:parallel wrap-arg-no-pattern-test
  (testing "wrap-arg returns value unchanged when arg-spec has no :pattern"
    (let [spec {:name "obj" :type "id"}
          val  {:address 0xBEEF}]
      (is (= val (patterns/wrap-arg spec val))))))

;; =============================================================================
;; wrap-delegate — idempotence
;; =============================================================================

(def ^:private mock-delegate {:address 0xDEAD})

(deftest wrap-delegate-idempotent-test
  (testing "wrap-delegate returns same ptr for identical map (hash-keyed)"
    (with-redefs [patterns/create-delegate-class! (fn [_ _ _] mock-delegate)]
      (mock/with-mock
        (let [dm  {:location-manager-did-update-locations (fn [] nil)}
              d1  (patterns/wrap-delegate dm "CLLocationManagerDelegate")
              d2  (patterns/wrap-delegate dm "CLLocationManagerDelegate")]
          ;; Both calls return the same object (retained by hash)
          (is (= d1 d2))
          ;; create-delegate-class! should be called only once (second is cache hit)
          ;; We can't easily count calls to the redefined fn, but we CAN verify
          ;; retention works: d2 comes from registry/retained, not create-delegate-class!
          (is (some? (registry/retained
                      [::patterns/delegate
                       (hash dm)
                       "CLLocationManagerDelegate"]))))))))

(deftest wrap-delegate-different-maps-test
  (testing "wrap-delegate creates distinct delegates for different maps"
    (with-redefs [patterns/create-delegate-class! (fn [_ _ _] (gensym "delegate"))]
      (mock/with-mock
        (let [dm1 {:location-manager-did-update-locations (fn [] :v1)}
              dm2 {:location-manager-did-update-locations (fn [] :v2)}
              d1  (patterns/wrap-delegate dm1 "CLLocationManagerDelegate")
              d2  (patterns/wrap-delegate dm2 "CLLocationManagerDelegate")]
          (is (not= d1 d2)))))))

;; =============================================================================
;; wrap-delegate — protocol method selector resolution
;; =============================================================================

(deftest wrap-delegate-resolves-selectors-test
  (testing "wrap-delegate uses protocol methods to build selector-enc-fn triples"
    (let [captured (atom nil)]
      (with-redefs [patterns/create-delegate-class!
                    (fn [class-name superclass sel-enc-fns]
                      (reset! captured {:class-name   class-name
                                        :superclass   superclass
                                        :sel-enc-fns (vec sel-enc-fns)})
                      mock-delegate)]
        (mock/with-mock
          (let [dm {:location-manager-did-update-locations (fn [] nil)}]
            (patterns/wrap-delegate dm "CLLocationManagerDelegate")
            ;; Verify selector resolution
            (let [{:keys [superclass sel-enc-fns]} @captured]
              (is (= "NSObject" superclass))
              ;; Should have found selector for :location-manager-did-update-locations
              (is (= 1 (count sel-enc-fns)))
              (is (= "locationManager:didUpdateLocations:" (first (first sel-enc-fns)))))))))))

;; =============================================================================
;; Full pipeline: api/call with :delegate arg
;; =============================================================================

(deftest api-call-delegate-arg-test
  (testing "api/call routes :delegate arg through patterns/wrap-delegate"
    (let [mock-inst {:address 0xFADE}]
      (with-redefs [patterns/create-delegate-class! (fn [_ _ _] mock-inst)]
        ;; with-responses mocks get-objc-class too (via with-mock)
        (mock/with-responses {"setDelegate:" nil}
          (let [mgr        (api/wrap "CLLocationManager" {:address 0xABCD})
                delegate-m {:location-manager-did-update-locations (fn [] nil)}
                _          (api/call mgr "setDelegate:" delegate-m)]
            ;; setDelegate: should have been dispatched
            (is (= 1 (count (mock/calls))))
            (is (= "setDelegate:" (:sel (first (mock/calls)))))
            ;; grease/objc-new wraps class ptr → {:instance-of cls}
            ;; so the delegate arg passed to setDelegate: is {:instance-of mock-inst}
            (is (= {:instance-of mock-inst}
                   (second (:args (first (mock/calls))))))))))))

;; =============================================================================
;; CoreLocation spec loaded
;; =============================================================================

(deftest ^:parallel corelocation-spec-loads-test
  (testing "CoreLocation spec is in registry after load!"
    (let [cs (registry/class-spec "CLLocationManager")]
      (is (some? cs))
      (is (= "CLLocationManager" (:name cs))))))

(deftest ^:parallel corelocation-protocol-loaded-test
  (testing "CLLocationManagerDelegate protocol is accessible"
    (let [proto (registry/protocol-spec "CLLocationManagerDelegate")]
      (is (some? proto))
      (is (= "CLLocationManagerDelegate" (:name proto)))
      (is (some #(= "locationManager:didUpdateLocations:" (:selector %))
                (:methods proto))))))

(deftest ^:parallel setdelegate-method-spec-test
  (testing "setDelegate: method-spec has :pattern :delegate"
    (let [m (registry/method-spec "CLLocationManager" "setDelegate:")]
      (is (some? m))
      (is (= :delegate (:pattern (first (:args m)))))
      (is (= "CLLocationManagerDelegate"
             (:protocol (first (:args m))))))))
