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

;; =============================================================================
;; KVO — init!, kvo-watch!, kvo-unwatch!
;; =============================================================================

(defn- reset-and-init!
  "Resets KVO state and calls init! with create-delegate-class! mocked."
  []
  (patterns/reset-state!)
  (patterns/init!))

(def ^:private mock-kvo-class {:class-name "GrseKVOObserver" :mocked true})

(deftest kvo-init-creates-class-test
  (testing "init! creates GrseKVOObserver class exactly once"
    (let [call-count (atom 0)]
      (with-redefs [patterns/create-delegate-class!
                    (fn [class-name _ _]
                      (swap! call-count inc)
                      (assoc mock-kvo-class :class-name class-name))]
        (mock/with-mock
          (patterns/reset-state!)
          (patterns/init!)
          (is (= 1 @call-count))
          ;; Second call is a no-op
          (patterns/init!)
          (is (= 1 @call-count)))))))

(deftest kvo-init-class-name-test
  (testing "init! registers class named GrseKVOObserver"
    (let [captured-name (atom nil)]
      (with-redefs [patterns/create-delegate-class!
                    (fn [class-name _ _]
                      (reset! captured-name class-name)
                      mock-kvo-class)]
        (mock/with-mock
          (patterns/reset-state!)
          (patterns/init!)
          (is (= "GrseKVOObserver" @captured-name)))))))

(deftest kvo-watch-dispatches-add-observer-test
  (testing "kvo-watch! calls addObserver:forKeyPath:options:context:"
    (with-redefs [patterns/create-delegate-class! (fn [_ _ _] mock-kvo-class)]
      (mock/with-mock
        (reset-and-init!)
        (let [mgr    (api/wrap "CLLocationManager" {:address 0xABCD})
              handle (patterns/kvo-watch! mgr "accuracy" (fn [_] nil))]
          (is (map? handle))
          (is (= "accuracy" (:key-path handle)))
          (is (some? (:observer handle)))
          (is (some? (:context handle)))
          (is (some #(= "addObserver:forKeyPath:options:context:" (:sel %))
                    (mock/calls))))))))

(deftest kvo-watch-returns-unique-contexts-test
  (testing "each kvo-watch! call gets a distinct context"
    (with-redefs [patterns/create-delegate-class! (fn [_ _ _] mock-kvo-class)]
      (mock/with-mock
        (reset-and-init!)
        (let [mgr (api/wrap "CLLocationManager" {:address 0xABCD})
              h1  (patterns/kvo-watch! mgr "accuracy" (fn [_] nil))
              h2  (patterns/kvo-watch! mgr "status" (fn [_] nil))]
          (is (not= (:context h1) (:context h2))))))))

(deftest kvo-unwatch-dispatches-remove-observer-test
  (testing "kvo-unwatch! calls removeObserver:forKeyPath:context:"
    (with-redefs [patterns/create-delegate-class! (fn [_ _ _] mock-kvo-class)]
      (mock/with-mock
        (reset-and-init!)
        (let [mgr    (api/wrap "CLLocationManager" {:address 0xABCD})
              handle (patterns/kvo-watch! mgr "accuracy" (fn [_] nil))
              _      (patterns/kvo-unwatch! handle)]
          (is (some #(= "removeObserver:forKeyPath:context:" (:sel %))
                    (mock/calls))))))))

(deftest api-watch-unwatch-test
  (testing "api/watch and api/unwatch delegate to patterns/kvo-watch! and kvo-unwatch!"
    (with-redefs [patterns/create-delegate-class! (fn [_ _ _] mock-kvo-class)]
      (mock/with-mock
        (reset-and-init!)
        (let [mgr    (api/wrap "CLLocationManager" {:address 0xABCD})
              handle (api/watch mgr :accuracy (fn [_] nil))]
          ;; watch uses keyword -> string conversion
          (is (= "accuracy" (:key-path handle)))
          (api/unwatch handle)
          (is (some #(= "removeObserver:forKeyPath:context:" (:sel %))
                    (mock/calls))))))))

;; =============================================================================
;; Completion handler — :completion-handler pattern
;; =============================================================================

(deftest completion-handler-void-block-test
  (testing "wrap-arg with :completion-handler :block-type :void creates void block"
    (mock/with-mock
      (let [spec {:name "handler" :type "id"
                  :pattern :completion-handler :block-type :void}
            cb   (fn [] :done)
            blk  (patterns/wrap-arg spec cb)]
        (is (= :void (:block-type blk)))
        (is (fn? (:fn blk)))
        (is (= 1 (count (mock/captured-blocks))))))))

(deftest completion-handler-bool-error-block-test
  (testing "wrap-arg with :completion-handler :block-type :bool-error creates bool-error block"
    (mock/with-mock
      (let [spec {:name "handler" :type "id"
                  :pattern :completion-handler :block-type :bool-error}
            cb   (fn [_ _] :done)
            blk  (patterns/wrap-arg spec cb)]
        (is (= :bool-error (:block-type blk)))
        (is (= 1 (count (mock/captured-blocks))))))))

(deftest completion-handler-data-block-test
  (testing "wrap-arg with :completion-handler :block-type :data creates data block"
    (mock/with-mock
      (let [spec {:name "handler" :type "id"
                  :pattern :completion-handler :block-type :data}
            cb   (fn [_ _ _] :done)
            blk  (patterns/wrap-arg spec cb)]
        (is (= :data (:block-type blk)))
        (is (= 1 (count (mock/captured-blocks))))))))

(deftest completion-handler-default-void-test
  (testing "wrap-arg :completion-handler defaults to :void when :block-type absent"
    (mock/with-mock
      (let [spec {:name "handler" :type "id" :pattern :completion-handler}
            blk  (patterns/wrap-arg spec (fn [] nil))]
        (is (= :void (:block-type blk)))))))

(deftest invoke-block-helper-test
  (testing "invoke-block! calls the captured block fn"
    (mock/with-mock
      (let [result (atom nil)
            spec   {:name "handler" :type "id"
                    :pattern :completion-handler :block-type :bool-error}
            _blk   (patterns/wrap-arg spec (fn [ok _err] (reset! result ok)))]
        (mock/invoke-block! (first (mock/captured-blocks)) 1 nil)
        (is (= 1 @result))))))
