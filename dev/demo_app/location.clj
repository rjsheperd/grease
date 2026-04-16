;; dev/demo_app/location.clj — CLLocationManager management for the demo app.
;;
;; @last-location holds the latest CLLocation pointer.
;; start! accepts an on-location-fn callback (fn [loc]) to avoid coupling this
;; namespace to demo_app.clj's UI atoms.
;;
;; CLLocationCoordinate2D is an ARM64 HFA (2 doubles in d0/d1).  libffi
;; incorrectly prepends a hidden stret pointer when used as a composite return
;; type, shifting receiver/selector and crashing objc_msgSend.  Scalar
;; C shims (grease_location_latitude/longitude) are used instead.
;; horizontalAccuracy is a plain double — uses msg-send :float64 directly.

(ns demo-app.location
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [grease.ios.api                :as ios]
            [grease.ios.foundation         :as f]
            [grease.ios.objc               :as objc-rt]
            [grease.ios.repl               :refer [on-main]]))

;; Latest CLLocation pointer, or nil before the first GPS fix.
(defonce last-location (atom nil))

;; Retained CLLocationManager + delegate — must stay in an atom to prevent ARC GC.
(defonce ^:private mgr-state (atom nil))

;; ─────────────────────────────────────────────────────────────────────────────
;; CLLocation accessors — C shims bypass ARM64 HFA struct-return issues
;; ─────────────────────────────────────────────────────────────────────────────

(defn latitude
  "Returns the latitude of a CLLocation pointer as a double.
  Uses grease_location_latitude C shim (ARM64 HFA struct return bypass)."
  [loc]
  (ffi/call "grease_location_latitude" :float64 :pointer loc))

(defn longitude
  "Returns the longitude of a CLLocation pointer as a double.
  Uses grease_location_longitude C shim (ARM64 HFA struct return bypass)."
  [loc]
  (ffi/call "grease_location_longitude" :float64 :pointer loc))

(defn coordinate
  "Returns {:latitude double :longitude double} from a CLLocation pointer."
  [loc]
  {:latitude (latitude loc) :longitude (longitude loc)})

(defn accuracy
  "Returns the horizontal accuracy of a CLLocation pointer in metres."
  [loc]
  (objc-rt/msg-send :float64 loc "horizontalAccuracy"))

;; ─────────────────────────────────────────────────────────────────────────────
;; Lifecycle
;; ─────────────────────────────────────────────────────────────────────────────

(defn start!
  "Starts CLLocationManager. on-location-fn is called with each new CLLocation
  pointer as (fn [loc]) on the GPS callback thread.

  Requests when-in-use authorization if not already granted. Retains the
  manager in @mgr-state to prevent ARC collection."
  [on-location-fn]
  (on-main
   (let [mgr (ios/make "CLLocationManager" "new")]
     (ios/call mgr :request-when-in-use-authorization)
     (ios/call mgr "setDelegate:"
               {:location-manager-did-update-locations
                (fn [_self _cmd _mgr locs]
                  (let [loc (last (f/nsarray->vec locs))]
                    (reset! last-location loc)
                    (on-location-fn loc)))

                :location-manager-did-fail-with-error
                (fn [_self _cmd _mgr err]
                  (println "CLLocation error:"
                           (f/nsstring->str
                            (objc-rt/msg-send :pointer err
                                              "localizedDescription"))))})
     (ios/call mgr :start-updating-location)
     (reset! mgr-state mgr))))

(defn stop!
  "Stops CLLocationManager updates and releases the retained manager."
  []
  (when-let [mgr @mgr-state]
    (on-main (ios/call mgr :stop-updating-location)))
  (reset! mgr-state nil))

(defn clear!
  "Stops updates and clears @last-location. Call before rebuild to start fresh."
  []
  (stop!)
  (reset! last-location nil))
