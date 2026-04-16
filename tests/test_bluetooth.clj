;; tests/test_bluetooth.clj — Integration test for grease.ios.bluetooth.
;;
;; Tests:
;;   1. CBCentralManager created via start-scanning!
;;   2. bt-state updated from :unknown (waits for CB state callback)
;;   3. scan! called without crash
;;   4. peripherals atom is a vector
;;   5. stop-scanning! without crash
;;
;; Note: does NOT wait for discovered peripherals — requires a real BLE environment.
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_bluetooth.clj

(load-file "tests/test_runner.clj")
(connect!)

(check "1. CBCentralManager created via start-scanning!"
  "(do
     (require '[grease.ios.bluetooth :as bt])
     (require '[grease.ios.repl :refer [on-main]])
     (some? (on-main (bt/start-scanning!))))"
  #(= "true" (:value %))
  :timeout 15000)

(check "2. bt-state updated from :unknown"
  "(do
     (require '[grease.ios.bluetooth :as bt])
     (loop [n 0]
       (let [st @bt/bt-state]
         (if (or (not= st :unknown) (> n 20))
           st
           (do (deref (promise) 300 nil) (recur (inc n)))))))"
  #(and (:ok? %) (some-> (:value %) (not= ":unknown")))
  :timeout 20000)

(check "3. scan! called without crash"
  "(do
     (require '[grease.ios.bluetooth :as bt])
     (require '[grease.ios.repl :refer [on-main]])
     (when (= @bt/bt-state :powered-on)
       (on-main (bt/scan!)))
     :scan-called)"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "scan-called"))))

(check "4. peripherals atom is a vector"
  "(do
     (require '[grease.ios.bluetooth :as bt])
     (vector? @bt/peripherals))"
  #(= "true" (:value %)))

(check "5. stop-scanning! without crash"
  "(do
     (require '[grease.ios.bluetooth :as bt])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (bt/stop-scanning!))
     :stopped)"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "stopped"))))

(run-suite "grease.ios.bluetooth")
