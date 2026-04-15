(ns grease.ios.corebluetooth-test
  "Tests for CoreBluetooth.edn spec — Phase 5.1 gate.

  Verifies spec loading, class/protocol/enum accessibility, and the
  :delegate pattern on CBCentralManager initWithDelegate:queue:.
  All tests run on plain JVM (no iOS hardware required)."
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
    (api/load!)
    (f)))

;; =============================================================================
;; Spec loading — classes
;; =============================================================================

(deftest ^:parallel cbcentralmanager-spec-test
  (testing "CBCentralManager class spec loads"
    (let [cs (registry/class-spec "CBCentralManager")]
      (is (some? cs))
      (is (= "CBCentralManager" (:name cs)))
      (let [init-sels (set (map :selector (:init cs)))]
        (is (contains? init-sels "alloc"))
        (is (contains? init-sels "initWithDelegate:queue:")))
      (let [method-sels (set (map :selector (:methods cs)))]
        (is (contains? method-sels "state"))
        (is (contains? method-sels "scanForPeripheralsWithServices:options:"))
        (is (contains? method-sels "stopScan"))
        (is (contains? method-sels "connectPeripheral:options:"))
        (is (contains? method-sels "cancelPeripheralConnection:"))))))

(deftest ^:parallel cbperipheral-spec-test
  (testing "CBPeripheral class spec loads with key methods"
    (let [cs (registry/class-spec "CBPeripheral")]
      (is (some? cs))
      (let [sels (set (map :selector (:methods cs)))]
        (is (contains? sels "name"))
        (is (contains? sels "state"))
        (is (contains? sels "services"))
        (is (contains? sels "discoverServices:"))))))

;; =============================================================================
;; Protocol
;; =============================================================================

(deftest ^:parallel cbcentralmanager-delegate-protocol-test
  (testing "CBCentralManagerDelegate protocol is accessible with all 5 methods"
    (let [proto (registry/protocol-spec "CBCentralManagerDelegate")]
      (is (some? proto))
      (is (= "CBCentralManagerDelegate" (:name proto)))
      (let [sels (set (map :selector (:methods proto)))]
        (is (contains? sels "centralManagerDidUpdateState:"))
        (is (contains? sels "centralManager:didDiscoverPeripheral:advertisementData:RSSI:"))
        (is (contains? sels "centralManager:didConnectPeripheral:"))
        (is (contains? sels "centralManager:didFailToConnectPeripheral:error:"))
        (is (contains? sels "centralManager:didDisconnectPeripheral:error:"))))))

;; =============================================================================
;; Enum
;; =============================================================================

(deftest ^:parallel cbmanagerstate-enum-test
  (testing "CBManagerState enum values match oracle integers"
    (is (= 0 (registry/enum-raw-for "CBManagerState" :cb-manager-state-unknown)))
    (is (= 1 (registry/enum-raw-for "CBManagerState" :cb-manager-state-resetting)))
    (is (= 2 (registry/enum-raw-for "CBManagerState" :cb-manager-state-unsupported)))
    (is (= 3 (registry/enum-raw-for "CBManagerState" :cb-manager-state-unauthorized)))
    (is (= 4 (registry/enum-raw-for "CBManagerState" :cb-manager-state-powered-off)))
    (is (= 5 (registry/enum-raw-for "CBManagerState" :cb-manager-state-powered-on)))))

(deftest ^:parallel cbmanagerstate-via-api-test
  (testing "api/enum resolves CBManagerState powered-on"
    (is (= 5 (api/enum "CBManagerState" :cb-manager-state-powered-on)))))

;; =============================================================================
;; initWithDelegate:queue: — :delegate pattern arg
;; =============================================================================

(deftest ^:parallel initwithdelegate-delegate-pattern-test
  (testing "initWithDelegate:queue: has :delegate pattern on first arg"
    (let [m    (registry/method-spec "CBCentralManager" "initWithDelegate:queue:")
          args (:args m)]
      (is (= 2 (count args)))
      (is (= :delegate (:pattern (first args))))
      (is (= "CBCentralManagerDelegate" (:protocol (first args))))
      ;; second arg is queue (no pattern)
      (is (nil? (:pattern (second args)))))))

;; =============================================================================
;; Delegate selector resolution via wrap-delegate
;; =============================================================================

(deftest cbcentralmanager-delegate-wiring-test
  (testing "wrap-delegate resolves CBCentralManagerDelegate selectors"
    (let [captured (atom nil)]
      (with-redefs [patterns/create-delegate-class!
                    (fn [_ _ sel-enc-fns]
                      (reset! captured (mapv first sel-enc-fns))
                      {:mocked true})]
        (mock/with-mock
          (patterns/wrap-delegate
           {:central-manager-did-update-state (fn [& _] nil)
            :central-manager-did-discover-peripheral-advertisement-data-rssi
            (fn [& _] nil)}
           "CBCentralManagerDelegate")
          (let [sels (set @captured)]
            (is (contains? sels "centralManagerDidUpdateState:"))
            (is (contains? sels
                           "centralManager:didDiscoverPeripheral:advertisementData:RSSI:"))))))))

;; =============================================================================
;; state method — NSInteger return (JVM-safe)
;; =============================================================================

(deftest state-dispatch-test
  (testing "api/call state returns raw NSInteger (mocked as 5 = powered-on)"
    (mock/with-responses {"state" 5}
      (let [mgr (api/wrap "CBCentralManager" {:address 0xB0BB})]
        (is (= 5 (api/call mgr "state")))))))

;; =============================================================================
;; void methods dispatch correctly
;; =============================================================================

(deftest stopscan-dispatch-test
  (testing "api/call stopScan dispatches correctly"
    (mock/with-responses {"stopScan" nil}
      (let [mgr (api/wrap "CBCentralManager" {:address 0xB0BB})]
        (api/call mgr "stopScan")
        (is (= 1 (count (mock/calls))))
        (is (= "stopScan" (:sel (first (mock/calls)))))))))
