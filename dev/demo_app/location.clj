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
;;
;; Delegate pattern: uses defclass + raw msg-send setDelegate: (NOT ios/call
;; with a delegate map).  The ios/call / patterns/wrap-delegate / make-imp path
;; dispatches Clojure callbacks to a send-off pool thread; by then ARC has
;; released the locs NSArray arg, causing EXC_BAD_ACCESS in send-off-pool-N.
;; defclass callbacks run synchronously on the ObjC GPS thread where locs is
;; still live.

(ns demo-app.location
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [grease.ios.foundation         :as f]
            [grease.ios.objc               :as objc-rt]
            [grease.ios.repl               :refer [on-main]]))

;; Latest CLLocation pointer, or nil before the first GPS fix.
;; WARNING: raw ObjC pointer — only safe to dereference from within the GPS
;; callback or immediately after stop! (i.e. while CLLocationManager is still
;; retaining it).  Use @last-coord for safe scalar access from any thread.
(defonce last-location (atom nil))

;; Latest location as a safe Clojure map — extracted inside the GPS callback
;; while loc is guaranteed alive.  Safe to read from any thread at any time.
(defonce last-coord (atom nil))

;; Retained CLLocationManager + delegate — must stay in atoms to prevent ARC GC.
(defonce ^:private mgr-state      (atom nil))
(defonce ^:private delegate-state (atom nil))

;; Mutable callback slot — set by start! before delegate fires.
;; defclass is loaded once at file-load time; the callback is indirected here
;; so start! can supply a new fn without re-registering the ObjC class.
(defonce ^:private on-location-fn-atom (atom (fn [_] nil)))

;; ObjC delegate class — registered once per process lifetime.
;; Re-registering the same class name crashes with a duplicate-class error.
;; The sentinel defonce lets this file be re-evaluated safely; defclass is
;; skipped if the class was already registered in this process.
(defonce ^:private _delegate-registered (atom false))

(when-not @_delegate-registered
  (objc-rt/defclass LocationDelegate "NSObject"
    "locationManager:didUpdateLocations:" "v@:@@"
    (fn [_self _cmd _mgr locs]
      ;; Extract scalar values while loc is still guaranteed live (inside
      ;; the GPS callback, before ARC can release it).  Store both the raw
      ;; pointer (@last-location) and a safe Clojure map (@last-coord).
      (let [loc (last (f/nsarray->vec locs))
            lat (ffi/call "grease_location_latitude"  :float64 :pointer loc)
            lng (ffi/call "grease_location_longitude" :float64 :pointer loc)
            acc (objc-rt/msg-send :float64 loc "horizontalAccuracy")]
        (reset! last-location loc)
        (reset! last-coord {:latitude lat :longitude lng :accuracy acc})
        (@on-location-fn-atom loc)))
    "locationManager:didFailWithError:" "v@:@@"
    (fn [_self _cmd _mgr err]
      (println "CLLocation error:"
               (f/nsstring->str
                (objc-rt/msg-send :pointer err "localizedDescription")))))
  (reset! _delegate-registered true))

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
  manager and delegate in atoms to prevent ARC collection."
  [on-location-fn]
  (reset! on-location-fn-atom on-location-fn)
  (on-main
   (let [mgr (objc-rt/new-instance "CLLocationManager")
         d   (objc-rt/new-instance LocationDelegate)]
     (reset! delegate-state d)
     (reset! mgr-state mgr)
     (objc-rt/msg-send :void mgr "setDelegate:" :pointer d)
     (objc-rt/msg-send :void mgr "requestWhenInUseAuthorization")
     (objc-rt/msg-send :void mgr "startUpdatingLocation"))))

(defn stop!
  "Stops CLLocationManager updates."
  []
  (when-let [mgr @mgr-state]
    (on-main (objc-rt/msg-send :void mgr "stopUpdatingLocation")))
  (reset! mgr-state nil))

(defn clear!
  "Stops updates, releases the delegate, and clears @last-location."
  []
  (stop!)
  (reset! delegate-state nil)
  (reset! last-location nil))
