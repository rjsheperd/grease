;; tests/test_location.clj — Integration test for CLLocationManager GPS.
;;
;; Loads dev/test_location.clj, starts location updates, waits up to 20s
;; for a GPS fix, and verifies the last-location atom is non-nil.
;;
;; Prerequisites:
;;   - App running on device (debug-demo-app --noinstall)
;;   - Tap "Allow" on the location permission dialog when it appears
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_location.clj

(load-file "tests/test_runner.clj")
(connect!)

(def location-wait-ms 20000)

;; Step 1: load the test namespace
(let [{:keys [ok? err]} (eval! (slurp "dev/test_location.clj") {:timeout 15000})]
  (when-not ok?
    (println (str "FATAL: could not load dev/test_location.clj\n" err))
    (System/exit 1)))
(println "[OK  ] 1. Loaded dev/test_location.clj")

;; Step 2: start location updates
(eval! "(dev.test-location/start-location-updates!)" {:timeout 8000})
(println "[OK  ] 2. start-location-updates! dispatched")

;; Step 3: check dispatch error
(check "3. No dispatch error"
  "(str (dev.test-location/error))"
  #(and (:ok? %) (= "nil" (:value %))))

;; Step 4: delegate was retained
(check "4. held-delegate is non-nil"
  "(some? @dev.test-location/held-delegate)"
  #(= "true" (:value %)))

;; Step 5: wait for GPS fix
(println (str "\n>>> Tap \"Allow\" if the location dialog appeared"))
(println (str ">>> Waiting " (/ location-wait-ms 1000) "s for GPS fix…\n"))
(Thread/sleep location-wait-ms)

;; Step 6: verify last-location
(check "5. last-location is non-nil (GPS fix received)"
  "(some? @dev.test-location/last-location)"
  #(= "true" (:value %))
  :timeout 5000)

(run-suite "CLLocationManager GPS")
