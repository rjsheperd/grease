(ns grease.ios.bluetooth
  "CoreBluetooth delegate and scanning helpers for the nREPL.

  Uses [[grease.ios.repl/defclass!]] to define a versioned
  CBCentralManagerDelegate on each eval, and [[grease.ios.repl/on-main]]
  for all UIKit / CB calls (CBCentralManager must be created on the main thread).

  Quick reference:

    ;; Start scanning and print discovered peripherals
    (bt/start-scanning!)

    ;; Stop scanning
    (bt/stop-scanning!)

    ;; Inspect discovered peripherals
    @bt/peripherals        ;; => [{:name \"My Device\" :rssi -65} ...]
    @bt/bt-state           ;; => :powered-on | :powered-off | :unauthorized | ..."
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]
            [grease.ios.repl :refer [defclass!]]))

;; =============================================================================
;; State atoms
;; =============================================================================

(def ^{:doc "Vec of discovered peripheral maps {:name :rssi :ptr}."}
  peripherals (atom []))

(def ^{:doc "Current CBManagerState as a keyword."}
  bt-state (atom :unknown))

(def ^:private manager-atom (atom nil))
(def ^:private delegate-atom (atom nil))

;; =============================================================================
;; State helpers
;; =============================================================================

(def ^:private state-keywords
  {0 :unknown
   1 :resetting
   2 :unsupported
   3 :unauthorized
   4 :powered-off
   5 :powered-on})

(defn- peripheral-name
  "Returns the peripheral's name string, or \"<unnamed>\" if nil."
  [peripheral]
  (let [name-ptr (objc-rt/msg-send :pointer peripheral "name")]
    (if name-ptr
      (f/nsstring->str name-ptr)
      "<unnamed>")))

;; =============================================================================
;; Delegate definition
;; =============================================================================

(defclass! GreaseBTDelegate "NSObject"

  "centralManagerDidUpdateState:" "v@:@"
  (fn [_self _cmd mgr]
    (let [state-int (objc-rt/msg-send :int64 mgr "state")
          kw        (get state-keywords state-int :unknown)]
      (reset! bt-state kw)
      (println "[BT] state:" kw)
      (when (= kw :powered-on)
        (println "[BT] powered on — ready to scan"))))

  "centralManager:didDiscoverPeripheral:advertisementData:RSSI:" "v@:@@@q"
  (fn [_self _cmd _mgr peripheral _adv rssi]
    (let [entry {:name (peripheral-name peripheral)
                 :rssi rssi
                 :ptr  peripheral}]
      (swap! peripherals conj entry)
      (println "[BT] found:" (:name entry) "RSSI:" rssi)))

  "centralManager:didConnectPeripheral:" "v@:@@"
  (fn [_self _cmd _mgr peripheral]
    (println "[BT] connected:" (peripheral-name peripheral)))

  "centralManager:didFailToConnectPeripheral:error:" "v@:@@@"
  (fn [_self _cmd _mgr peripheral err]
    (let [msg (if err
                (f/nsstring->str (objc-rt/msg-send :pointer err "localizedDescription"))
                "unknown error")]
      (println "[BT] failed to connect:" (peripheral-name peripheral) "-" msg))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start-scanning!
  "Creates a CBCentralManager, sets the delegate, and starts scanning
  for all peripherals.  Results accumulate in [[peripherals]].
  Must be called from (or dispatched to) the main thread."
  []
  (reset! peripherals [])
  (let [cls  (grease/get-objc-class "CBCentralManager")
        d    (objc-rt/new-instance GreaseBTDelegate)
        mgr  (objc-rt/msg-send :pointer cls "alloc")]
    (reset! delegate-atom d)
    (reset! manager-atom
            (objc-rt/msg-send :pointer mgr
                              "initWithDelegate:queue:"
                              :pointer d
                              :pointer nil))
    (println "[BT] manager created, waiting for powered-on state...")
    @manager-atom))

(defn stop-scanning!
  "Stops BLE scanning. Noop if manager was never created."
  []
  (when-let [mgr @manager-atom]
    (objc-rt/msg-send :void mgr "stopScan")
    (println "[BT] scan stopped")))

(defn scan!
  "Tells the manager to scan for all peripherals (nil = all services).
  Call after state is :powered-on."
  []
  (when-let [mgr @manager-atom]
    (objc-rt/msg-send :void mgr
                      "scanForPeripheralsWithServices:options:"
                      :pointer nil
                      :pointer nil)))
