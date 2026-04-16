;; dev/demo_app/map.clj — MKMapView helpers for the demo app.
;;
;; setRegion:animated: passes MKCoordinateRegion by value (32 bytes, 4 doubles).
;; grease.ios.invoke/dispatch! handles this via ffi/call-ptr with a composite
;; ffi_type — libffi generates correct ARM64 HFA calling code. No C shim needed.
;;
;; Screen size uses grease_get_frame_w/h on the parent view (avoids CGRect
;; struct return from UIScreen.bounds and respects tab bar layout).

(ns demo-app.map
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease     :as grease]
            [grease.ios.api               :as ios]
            [grease.ios.objc               :as objc-rt]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Screen geometry (scalar shims — avoids CGRect struct return)
;; ─────────────────────────────────────────────────────────────────────────────

(defn screen-width
  "Returns the screen width in points via grease_screen_width C shim."
  []
  (ffi/call "grease_screen_width" :float64))

(defn screen-height
  "Returns the screen height in points via grease_screen_height C shim."
  []
  (ffi/call "grease_screen_height" :float64))

;; ─────────────────────────────────────────────────────────────────────────────
;; Map region — libffi composite type dispatch (no C shim)
;; ─────────────────────────────────────────────────────────────────────────────

(defn set-region!
  "Centers map-view on [lat lng] with the given degree span (animated if animated?).
  Passes MKCoordinateRegion as a struct via ffi/call-ptr — no C shim needed.
  Must be called on the main thread."
  [map-view lat lng lat-delta lng-delta animated?]
  (ios/call* map-view "MKMapView" "setRegion:animated:"
             {:center {:latitude lat :longitude lng}
              :span   {:latitude-delta lat-delta :longitude-delta lng-delta}}
             animated?))

;; ─────────────────────────────────────────────────────────────────────────────
;; Default region constants
;; ─────────────────────────────────────────────────────────────────────────────

(def apple-hq-lat   37.3346)
(def apple-hq-lng -122.0090)
(def default-span    0.05)

;; ─────────────────────────────────────────────────────────────────────────────
;; User location tracking
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private miles->meters 1609.344)

(defn follow-user!
  "Switches map-view to MKUserTrackingModeFollow (value 1) so MapKit keeps the
  map centered on the GPS fix automatically. Safe to call from any thread —
  dispatches to the main thread asynchronously."
  [map-view]
  (grease/dispatch-main-async
   (fn []
     (objc-rt/msg-send :void map-view
                       "setUserTrackingMode:animated:"
                       :int64 1 :int8 1))))

(defn add-circle!
  "Adds (or updates) a translucent blue circle overlay on map-view centred on
  [lat lng] with the given radius in miles. Safe to call from any thread."
  [map-view lat lng radius-miles]
  (grease/dispatch-main-async
   (fn []
     (ffi/call "grease_map_add_circle" :void
               :pointer map-view
               :float64 lat
               :float64 lng
               :float64 (* radius-miles miles->meters)))))
