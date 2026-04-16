;; tests/test_engine_corelocation.clj — Phase 2.3 on-device CLLocationManager via engine API.
;;
;; Validates the full delegate pipeline using grease.ios.api on real hardware.
;; Requires location permission — tap "Allow" when the permission dialog appears.
;;
;; Prerequisites:
;;   - App running on device (debug-demo-app --noinstall)
;;   - Tap "Allow" on the location permission dialog when it appears
;;
;; Usage:
;;   clj -M tests/test_engine_corelocation.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. Engine namespace loaded ──────────────────────────────────────────────

(check "1. grease.ios.api namespace accessible"
  "(do (require '[grease.ios.api :as api]) :ok)"
  #(= ":ok" (:value %)))

;; ─── 2. CLLocationManager instantiation via engine ───────────────────────────

(check "2. CLLocationManager new via engine"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (some? (api/make \"CLLocationManager\" \"new\"))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 3. Request authorization (main thread) ──────────────────────────────────

(check "3. requestWhenInUseAuthorization dispatches without error"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (def clm-state (atom {:mgr nil :last-loc nil}))
     (on-main
       (let [mgr (api/make \"CLLocationManager\" \"new\")]
         (swap! clm-state assoc :mgr mgr)
         (api/call mgr \"requestWhenInUseAuthorization\")))
     :ok)"
  #(= ":ok" (:value %))
  :timeout 10000)

;; ─── 4. Set delegate and start updating ─────────────────────────────────────

(check "4. setDelegate: + startUpdatingLocation dispatch without error"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (def clm-loc (atom nil))
     (on-main
       (let [mgr  (:mgr @clm-state)
             dlgt {\"locationManager:didUpdateLocations:\"
                   (fn [_self _cmd _mgr locs]
                     (reset! clm-loc locs))
                   \"locationManager:didFailWithError:\"
                   (fn [_self _cmd _mgr _err] nil)
                   \"locationManagerDidChangeAuthorization:\"
                   (fn [_self _cmd _mgr] nil)}]
         (api/call mgr \"setDelegate:\" dlgt)
         (api/call mgr \"startUpdatingLocation\")))
     :ok)"
  #(= ":ok" (:value %))
  :timeout 15000)

;; ─── 5. Wait up to 20s for location callback ─────────────────────────────────

(check "5. Location callback received within 20s (tap Allow when prompted)"
  "(let [deadline (+ (System/currentTimeMillis) 20000)]
     (loop []
       (if (some? @clm-loc)
         :got-location
         (if (> (System/currentTimeMillis) deadline)
           :timeout
           (do (deref (promise) 500 nil) (recur))))))"
  #(= ":got-location" (:value %))
  :timeout 25000)

;; ─── 6. Verify location value is non-nil ─────────────────────────────────────

(check "6. clm-loc atom is non-nil after callback"
  "(some? @clm-loc)"
  #(= "true" (:value %)))

(run-suite "grease.ios.engine CoreLocation (Phase 2.3)")
