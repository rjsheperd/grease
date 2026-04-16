;; dev/demo_app/location.clj — CLLocationManager management for the demo app.
;;
;; @last-location holds the latest CLLocation pointer.
;; start! accepts an on-location-fn callback (fn [loc]) to avoid coupling this
;; namespace to demo_app.clj's UI atoms.
;;
;; CLLocationCoordinate2D is an ARM64 HFA (2 doubles in d0/d1).  The fixed
;; clj-libffi fork (rjsheperd/clj-libffi) zeroes :size/:alignment in
;; struct-def->ffi-type so ffi_prep_cif triggers initialize_aggregate, selecting
;; the correct HFA register convention.  coordinate is dispatched via
;; grease.ios.invoke/dispatch! → ffi/call-ptr — no C shim needed.
;;
;; Delegate pattern: uses defclass + raw msg-send setDelegate: (NOT ios/call
;; with a delegate map).  The ios/call / patterns/wrap-delegate / make-imp path
;; dispatches Clojure callbacks to a send-off pool thread; by then ARC has
;; released the locs NSArray arg, causing EXC_BAD_ACCESS in send-off-pool-N.
;; defclass callbacks run synchronously on the ObjC GPS thread where locs is
;; still live.

(ns demo-app.location
  (:require [grease.ios.foundation :as f]
            [grease.ios.objc       :as objc-rt]
            [grease.ios.repl       :refer [on-main]]))

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

;; ─────────────────────────────────────────────────────────────────────────────
;; CLLocation accessors — struct-return dispatch via ffi/call-ptr
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private coordinate-spec
  {:selector "coordinate"
   :encoding "{CLLocationCoordinate2D=dd}@:"
   :args     []
   :return   "CLLocationCoordinate2D"})

(defn coordinate
  "Returns {:latitude double :longitude double} from a CLLocation pointer."
  [loc]
  (let [c ((requiring-resolve 'grease.ios.invoke/dispatch!)
           loc "coordinate" coordinate-spec [])]
    {:latitude  (get c :latitude)
     :longitude (get c :longitude)}))

(defn latitude
  "Returns the latitude of a CLLocation pointer as a double."
  [loc]
  (:latitude (coordinate loc)))

(defn longitude
  "Returns the longitude of a CLLocation pointer as a double."
  [loc]
  (:longitude (coordinate loc)))

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
      (let [loc   (last (f/nsarray->vec locs))
            coord (coordinate loc)
            acc   (objc-rt/msg-send :float64 loc "horizontalAccuracy")]
        (reset! last-location loc)
        (reset! last-coord (assoc coord :accuracy acc))
        (@on-location-fn-atom loc)))
    "locationManager:didFailWithError:" "v@:@@"
    (fn [_self _cmd _mgr err]
      (println "CLLocation error:"
               (f/nsstring->str
                (objc-rt/msg-send :pointer err "localizedDescription")))))
  (reset! _delegate-registered true))

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
