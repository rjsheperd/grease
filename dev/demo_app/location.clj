;; dev/demo_app/location.clj — CLLocationManager management for the demo app.
;;
;; @last-location holds the latest CLLocation pointer.
;; start! accepts an on-location-fn callback (fn [loc]) to avoid coupling this
;; namespace to demo_app.clj's UI atoms.
;;
;; Struct returns (CLLocationCoordinate2D from -coordinate, double from
;; -horizontalAccuracy) are handled by grease.ios.invoke/dispatch! via
;; ffi/call-ptr — no C shims required.

(ns demo-app.location
  (:require [grease.ios.api                :as ios]
            [grease.ios.foundation         :as f]
            [grease.ios.objc               :as objc-rt]
            [grease.ios.repl               :refer [on-main]]))

;; Latest CLLocation pointer, or nil before the first GPS fix.
(defonce last-location (atom nil))

;; Retained CLLocationManager + delegate — must stay in an atom to prevent ARC GC.
(defonce ^:private mgr-state (atom nil))

;; ─────────────────────────────────────────────────────────────────────────────
;; CLLocation accessors — struct-return via engine dispatch
;; ─────────────────────────────────────────────────────────────────────────────

(defn coordinate
  "Returns {:latitude double :longitude double} from a CLLocation pointer.
  Dispatched via ffi/call-ptr for correct ARM64 HFA struct return (d0/d1)."
  [loc]
  (ios/call* loc "CLLocation" "coordinate"))

(defn latitude
  "Returns the latitude of a CLLocation pointer as a double."
  [loc]
  (:latitude (coordinate loc)))

(defn longitude
  "Returns the longitude of a CLLocation pointer as a double."
  [loc]
  (:longitude (coordinate loc)))

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
