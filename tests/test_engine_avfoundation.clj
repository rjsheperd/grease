;; tests/test_engine_avfoundation.clj — Phase 3.2 on-device AVFoundation via engine API.
;;
;; Validates the AVFoundation engine pipeline on real hardware.
;; Tests that DON'T require camera permission run first.
;; Frame-capture test requires camera permission — tap "Allow" when prompted.
;;
;; Prerequisites:
;;   - App running on device (debug-demo-app --noinstall)
;;
;; Usage:
;;   clj -M tests/test_engine_avfoundation.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. Engine namespace loaded ──────────────────────────────────────────────

(check "1. AVFoundation spec loaded in registry"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.registry :as registry])
     (some? (registry/class-spec \"AVCaptureSession\")))"
  #(= "true" (:value %)))

;; ─── 2. AVCaptureSession instantiation ───────────────────────────────────────

(check "2. AVCaptureSession new via engine"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (some? (api/make \"AVCaptureSession\" \"new\"))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 3. AVCaptureVideoDataOutput instantiation ───────────────────────────────

(check "3. AVCaptureVideoDataOutput new via engine"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (some? (api/make \"AVCaptureVideoDataOutput\" \"new\"))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 4. Session isRunning returns false initially ────────────────────────────

(check "4. isRunning returns false on a new session"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [session (api/make \"AVCaptureSession\" \"new\")]
         (api/call session \"isRunning\"))))"
  #(= "false" (:value %))
  :timeout 10000)

;; ─── 5. Authorization status check (class method) ────────────────────────────

(check "5. authorizationStatusForMediaType: returns integer (0-3)"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (let [status (on-main
                    (api/class-call \"AVCaptureDevice\"
                                    \"authorizationStatusForMediaType:\"
                                    \"vide\"))]
       (and (integer? status) (<= 0 status 3))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 6. Default camera device (nil if not authorized) ────────────────────────

(check "6. defaultDeviceWithMediaType: returns non-exception result"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (try
       (on-main
         (let [dev (api/make \"AVCaptureDevice\" \"defaultDeviceWithMediaType:\" \"vide\")]
           (or (nil? dev) (some? dev))))
       (catch Exception e {:err (ex-message e)})))"
  #(= "true" (:value %))
  :timeout 10000)

(run-suite "grease.ios.engine AVFoundation (Phase 3.2)")
