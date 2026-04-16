;; tests/test_engine_corebluetooth.clj — Phase 5.2 on-device CoreBluetooth via engine API.
;;
;; Validates the CoreBluetooth engine pipeline on real hardware.
;; Bluetooth state test requires Bluetooth to be enabled on the device.
;;
;; Prerequisites:
;;   - App running on device (debug-demo-app --noinstall)
;;   - Bluetooth enabled on device
;;
;; Usage:
;;   clj -M tests/test_engine_corebluetooth.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. CoreBluetooth spec loaded ────────────────────────────────────────────

(check "1. CoreBluetooth spec loaded in registry"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.registry :as registry])
     (some? (registry/class-spec \"CBCentralManager\")))"
  #(= "true" (:value %)))

;; ─── 2. CBCentralManager alloc ───────────────────────────────────────────────

(check "2. CBCentralManager alloc returns ObjcObject"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (some? (api/make \"CBCentralManager\" \"alloc\"))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 3. CBCentralManager initWithDelegate:queue: on main thread ──────────────
;;
;; Uses com.phronemophobic.grease/dispatch-main-queue for the queue arg.
;; The delegate map uses the CBCentralManagerDelegate protocol.

(check "3. CBCentralManager initWithDelegate:queue: wires delegate without error"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (def cb-state (atom nil))
     (try
       (on-main
         (let [alloc (api/make \"CBCentralManager\" \"alloc\")
               dlgt  {:central-manager-did-update-state
                      (fn [_self _cmd mgr]
                        (reset! cb-state :got-update))}
               queue nil]
           (api/call alloc \"initWithDelegate:queue:\" dlgt queue)))
       :ok
       (catch Exception e {:err (ex-message e) :data (pr-str (ex-data e))})))"
  #(or (= ":ok" (:value %)) (some-> (:value %) (clojure.string/includes? ":ok")))
  :timeout 15000)

;; ─── 4. Wait up to 3s for state callback ─────────────────────────────────────

(check "4. centralManagerDidUpdateState: fires within 3s"
  "(let [deadline (+ (System/currentTimeMillis) 3000)]
     (loop []
       (if (some? @cb-state)
         :got-state
         (if (> (System/currentTimeMillis) deadline)
           :timeout
           (do (deref (promise) 200 nil) (recur))))))"
  #(= ":got-state" (:value %))
  :timeout 5000)

;; ─── 5. Verify CBPeripheral class exists in registry ─────────────────────────

(check "5. CBPeripheral class spec available"
  "(do
     (require '[grease.ios.registry :as registry])
     (some? (registry/class-spec \"CBPeripheral\")))"
  #(= "true" (:value %)))

;; ─── 6. CBManagerState enum resolves ─────────────────────────────────────────

(check "6. CBManagerState enum :powered-on resolves to 5"
  "(do
     (require '[grease.ios.api :as api])
     (api/enum \"CBManagerState\" :powered-on))"
  #(= "5" (:value %)))

(run-suite "grease.ios.engine CoreBluetooth (Phase 5.2)")
