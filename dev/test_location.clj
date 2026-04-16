;; dev/test_location.clj — CLLocationManager REPL integration test
;;
;; Registers a LocationDelegate ObjC class, starts a CLLocationManager on the
;; main thread, and waits for a GPS fix. Requires the app to have Location
;; Services permission (tap "Allow" when the dialog appears).
;;
;; Run via:
;;   python3 tests/run_test_location.py
;;
;; Or interactively from an nREPL connected to 192.168.0.111:23456:
;;   (load-file "dev/test_location.clj")
;;   (dev.test-location/start-location-updates!)
;;   ;; tap Allow on device ...
;;   @dev.test-location/last-location   ;; => non-nil pointer when fix arrives
;;
;; NOTE: ObjC class registration is permanent for the lifetime of the process.
;; Re-loading this file (and re-running defclass) in the same session will fail
;; with an ObjC duplicate-class error. Restart the app for a clean slate.

(ns dev.test-location
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.objc :as objc-rt]))

(def last-location
  "Most recent CLLocation array pointer from locationManager:didUpdateLocations:."
  (atom nil))

(def held-mgr
  "Holds the CLLocationManager pointer to prevent ARC from releasing it."
  (atom nil))

(def held-delegate
  "Holds the LocationDelegate pointer to prevent ARC from releasing it."
  (atom nil))

(def dispatch-error
  "Holds any error string thrown inside the main-thread dispatch block, or nil."
  (atom nil))

;; Register the delegate class. This happens once at load time.
(objc-rt/defclass LocationDelegate "NSObject"
  "locationManager:didUpdateLocations:" "v@:@@"
  (fn [_self _cmd _mgr locs]
    (println "[location] update — locs pointer:" locs)
    (reset! last-location locs)))

(defn start-location-updates!
  "Allocates a CLLocationManager, attaches LocationDelegate, and starts updates.
  Must be called from any thread — dispatches internally to the main thread.
  Returns :dispatched immediately; the callback fires asynchronously.

  After calling, tap 'Allow' on the device permission dialog, then check
  [[last-location]] or call [[location]] after a few seconds."
  []
  (reset! dispatch-error nil)
  (grease/dispatch-main-async
   (fn []
     (try
       (let [cls-mgr (grease/get-objc-class "CLLocationManager")
             mgr     (grease/objc-new cls-mgr)
             d       (objc-rt/new-instance LocationDelegate)]
         (reset! held-mgr mgr)
         (reset! held-delegate d)
         (objc-rt/msg-send :void mgr "setDelegate:" :pointer d)
         (objc-rt/msg-send :void mgr "requestWhenInUseAuthorization")
         (objc-rt/msg-send :void mgr "startUpdatingLocation")
         (println "[location] CLLocationManager started"))
       (catch Exception e
         (let [msg (str (class e) ": " (.getMessage e))]
           (reset! dispatch-error msg)
           (println "[location] DISPATCH ERROR:" msg))))))
  :dispatched)

(defn location
  "Returns the current value of [[last-location]] (nil until a fix arrives)."
  []
  @last-location)

(defn error
  "Returns the dispatch error string if startup failed, otherwise nil."
  []
  @dispatch-error)

(println "[test-location] loaded — call (dev.test-location/start-location-updates!) to begin")

(comment

  (dev.test-location/start-location-updates!)

  @last-location


  )
