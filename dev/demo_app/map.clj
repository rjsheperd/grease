;; dev/demo_app/map.clj — MKMapView helpers for the demo app.
;;
;; All map operations use scalar-arg C shims to bypass struct FFI limitations.
;; setRegion:animated: takes MKCoordinateRegion by value (32 bytes) — handled
;; by grease_set_map_region in Bridge.m.

(ns demo-app.map
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease     :as grease]
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
;; Map region (struct-arg bypass)
;; ─────────────────────────────────────────────────────────────────────────────

(defn set-region!
  "Centers map-view on [lat lng] with the given degree span (animated if animated?).
  Uses grease_set_map_region C shim to bypass the MKCoordinateRegion struct arg.
  Must be called on the main thread."
  [map-view lat lng lat-delta lng-delta animated?]
  (ffi/call "grease_set_map_region" :void
            :pointer map-view
            :float64 lat       :float64 lng
            :float64 lat-delta :float64 lng-delta
            :int8    (if animated? 1 0)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Default region constants
;; ─────────────────────────────────────────────────────────────────────────────

(def apple-hq-lat   37.3346)
(def apple-hq-lng -122.0090)
(def default-span    0.05)

;; ─────────────────────────────────────────────────────────────────────────────
;; User location tracking
;; ─────────────────────────────────────────────────────────────────────────────

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
