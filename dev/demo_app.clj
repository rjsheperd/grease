;; dev/demo_app.clj — Map + Tab Bar demo app
;;
;; Builds a UITabBarController with two tabs:
;;   Tab 0 — Map:  MKMapView, blue user-location dot, auto-follows GPS fix
;;   Tab 1 — Info: UILabel stack showing lat/lng/accuracy live from CLLocation
;;
;; Usage from nREPL after deploy:
;;   (load-file "dev/demo_app.clj")
;;   (demo-app/start!)          ; build UI + start GPS in one shot
;;   @demo-app/last-location    ; => CLLocation pointer after first GPS fix
;;
;; Hot-reload / teardown:
;;   (demo-app/restart!)

(ns demo-app
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease     :as grease]
            [grease.ios.api                :as ios]
            [grease.ios.objc               :as objc-rt]
            [grease.ios.foundation         :as f]
            [grease.ios.uikit              :as ui]
            [grease.ios.repl               :refer [on-main]]))

;; ─────────────────────────────────────────────────────────────────────────────
;; App state — all live ObjC pointers retained here to prevent ARC collection.
;; ─────────────────────────────────────────────────────────────────────────────

;; Holds all live ObjC handles for the running demo UI.
;; Keys: :tab-bar :map-nav :map-vc :info-nav :info-vc :map-view
;;       :info-labels {:lat-lbl :lng-lbl :acc-lbl}
;;       :location-mgr
(defonce app-state
  (atom {:tab-bar      nil
         :map-nav      nil
         :map-vc       nil
         :info-nav     nil
         :info-vc      nil
         :map-view     nil
         :info-labels  nil
         :location-mgr nil}))

;; Latest CLLocation pointer, or nil before first GPS fix.
(defonce last-location (atom nil))

;; ─────────────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn- get-class [n] (grease/get-objc-class n))

(defn- new-instance [class-name]
  (objc-rt/msg-send :pointer (get-class class-name) "new"))

(defn- screen-width  [] (ffi/call "grease_screen_width"  :float64))
(defn- screen-height [] (ffi/call "grease_screen_height" :float64))

;; ─────────────────────────────────────────────────────────────────────────────
;; Map region helper (C shim — MKCoordinateRegion struct-arg bypass)
;; ─────────────────────────────────────────────────────────────────────────────

(defn set-map-region!
  "Centers map-view on [lat lng] with the given degree span.
  Uses grease_set_map_region C shim to avoid the MKCoordinateRegion struct arg."
  [map-view lat lng lat-delta lng-delta animated?]
  (ffi/call "grease_set_map_region" :void
            :pointer map-view
            :float64 lat        :float64 lng
            :float64 lat-delta  :float64 lng-delta
            :int8    (if animated? 1 0)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Location scalar accessors (CLLocationCoordinate2D struct-return bypass)
;; ─────────────────────────────────────────────────────────────────────────────

(defn location-latitude  [loc] (ffi/call "grease_location_latitude"  :float64 :pointer loc))
(defn location-longitude [loc] (ffi/call "grease_location_longitude" :float64 :pointer loc))
(defn location-accuracy  [loc] (ffi/call "grease_location_accuracy"  :float64 :pointer loc))

;; ─────────────────────────────────────────────────────────────────────────────
;; Tab bar construction
;; ─────────────────────────────────────────────────────────────────────────────

(defn- make-tab-item!
  "Creates a UITabBarItem with a text title and no image."
  [title tag]
  (objc-rt/msg-send :pointer (get-class "UITabBarItem")
                    "initWithTitle:image:tag:"
                    :pointer (f/->nsstring title)
                    :pointer (f/null-ptr)
                    :int64   tag))

(defn- make-plain-vc!
  "Allocates a bare UIViewController with a solid system background color."
  [bg-selector]
  (let [vc (new-instance "UIViewController")
        v  (objc-rt/msg-send :pointer vc "view")]
    (objc-rt/msg-send :void v "setBackgroundColor:"
                      :pointer (objc-rt/msg-send :pointer
                                                 (get-class "UIColor")
                                                 bg-selector))
    vc))

(defn install-tab-bar!
  "Creates UITabBarController with Map + Info tabs and installs it as the
  app's rootViewController via GreaseHook.shared.window."
  []
  (on-main
   (let [map-vc   (make-plain-vc! "systemBackgroundColor")
         info-vc  (make-plain-vc! "systemGroupedBackgroundColor")
         map-nav  (objc-rt/msg-send :pointer (get-class "UINavigationController")
                                    "initWithRootViewController:" :pointer map-vc)
         info-nav (objc-rt/msg-send :pointer (get-class "UINavigationController")
                                    "initWithRootViewController:" :pointer info-vc)
         tab-bar  (new-instance "UITabBarController")
         vcs      (f/->nsarray [map-nav info-nav])]
     (objc-rt/msg-send :void map-vc  "setTabBarItem:" :pointer (make-tab-item! "Map"  0))
     (objc-rt/msg-send :void info-vc "setTabBarItem:" :pointer (make-tab-item! "Info" 1))
     (objc-rt/msg-send :void tab-bar "setViewControllers:animated:"
                       :pointer vcs :int8 0)
     (let [hook   (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared")
           window (objc-rt/msg-send :pointer hook "window")]
       (objc-rt/msg-send :void window "setRootViewController:" :pointer tab-bar))
     (swap! app-state assoc
            :tab-bar  tab-bar
            :map-vc   map-vc
            :info-vc  info-vc
            :map-nav  map-nav
            :info-nav info-nav))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Map view
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private apple-hq-lat   37.3346)
(def ^:private apple-hq-lng -122.0090)
(def ^:private default-span    0.05)

(defn install-map-view!
  "Creates a full-screen MKMapView in the Map tab, enables the blue
  user-location dot, and centers on Apple HQ as a placeholder."
  []
  (on-main
   (let [map-vc (:map-vc @app-state)
         root-v (objc-rt/msg-send :pointer map-vc "view")
         map-v  (new-instance "MKMapView")
         w      (screen-width)
         h      (screen-height)]
     (ui/set-frame! map-v 0.0 0.0 w h)
      ;; UIViewAutoresizingFlexibleWidth | FlexibleHeight = 2 | 16 = 18
     (objc-rt/msg-send :void map-v "setAutoresizingMask:" :int64 18)
     (objc-rt/msg-send :void map-v "setShowsUserLocation:" :int8 1)
     (set-map-region! map-v apple-hq-lat apple-hq-lng
                      default-span default-span false)
     (objc-rt/msg-send :void root-v "addSubview:" :pointer map-v)
     (swap! app-state assoc :map-view map-v))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Info tab labels
;; ─────────────────────────────────────────────────────────────────────────────

(defn- make-label!
  "Creates a centered UILabel at position [20, y] spanning the screen width."
  [text y]
  (let [lbl (new-instance "UILabel")
        w   (- (screen-width) 40.0)]
    (ui/set-frame! lbl 20.0 y w 44.0)
    (objc-rt/msg-send :void lbl "setText:"          :pointer (f/->nsstring text))
    (objc-rt/msg-send :void lbl "setTextAlignment:"  :int64 1) ; NSTextAlignmentCenter
    (objc-rt/msg-send :void lbl "setFont:"
                      :pointer (objc-rt/msg-send :pointer (get-class "UIFont")
                                                 "systemFontOfSize:" :float64 16.0))
    lbl))

(defn install-info-labels!
  "Builds the lat/lng/accuracy label stack in the Info tab view."
  []
  (on-main
   (let [info-vc (:info-vc @app-state)
         root-v  (objc-rt/msg-send :pointer info-vc "view")
         lat-lbl (make-label! "Lat: waiting for GPS…" 140.0)
         lng-lbl (make-label! "Lng: waiting for GPS…" 192.0)
         acc-lbl (make-label! "Accuracy: —"           244.0)]
     (doseq [l [lat-lbl lng-lbl acc-lbl]]
       (objc-rt/msg-send :void root-v "addSubview:" :pointer l))
     (swap! app-state assoc :info-labels {:lat-lbl lat-lbl
                                          :lng-lbl lng-lbl
                                          :acc-lbl acc-lbl}))))

(defn- update-info-labels!
  "Updates the Info tab labels from a CLLocation pointer. Called on GPS updates."
  [loc]
  (when-let [{:keys [lat-lbl lng-lbl acc-lbl]} (:info-labels @app-state)]
    (let [lat-v (location-latitude  loc)
          lng-v (location-longitude loc)
          acc-v (location-accuracy  loc)]
      (on-main
       (objc-rt/msg-send :void lat-lbl "setText:"
                         :pointer (f/->nsstring (format "Lat: %.6f" lat-v)))
       (objc-rt/msg-send :void lng-lbl "setText:"
                         :pointer (f/->nsstring (format "Lng: %.6f" lng-v)))
       (objc-rt/msg-send :void acc-lbl "setText:"
                         :pointer (f/->nsstring (format "Accuracy: ±%.0fm" acc-v)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; CoreLocation
;; ─────────────────────────────────────────────────────────────────────────────

(defn- enable-map-follow!
  "Switches the map to MKUserTrackingModeFollow (1) on first GPS fix."
  []
  (when-let [mv (:map-view @app-state)]
    (on-main
     (objc-rt/msg-send :void mv "setUserTrackingMode:animated:"
                       :int64 1 :int8 1))))

(defn start-location!
  "Starts CLLocationManager. Wires the delegate, requests authorization,
  and begins location updates. On first GPS fix, enables native map tracking."
  []
  (on-main
   (let [mgr (ios/make "CLLocationManager" "init")]
     (ios/call mgr :request-when-in-use-authorization)
     (ios/call mgr "setDelegate:"
               {:location-manager-did-update-locations
                (fn [_mgr locs]
                  (let [loc (last (f/nsarray->vec locs))]
                    (when (nil? @last-location) (enable-map-follow!))
                    (reset! last-location loc)
                    (update-info-labels! loc)))

                :location-manager-did-fail-with-error
                (fn [_mgr err]
                  (println "CLLocation error:"
                           (f/nsstring->str
                            (objc-rt/msg-send :pointer err
                                              "localizedDescription"))))})
     (ios/call mgr :start-updating-location)
     (swap! app-state assoc :location-mgr mgr))))

(defn stop-location!
  "Stops CLLocationManager updates."
  []
  (when-let [mgr (:location-mgr @app-state)]
    (on-main (ios/call mgr :stop-updating-location))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Top-level entry points
;; ─────────────────────────────────────────────────────────────────────────────

(defn start!
  "Full app setup in one shot.
  1. Builds the tab bar shell.
  2. Adds the MKMapView to the Map tab.
  3. Adds the label stack to the Info tab.
  4. Starts CLLocationManager."
  []
  (install-tab-bar!)
  (install-map-view!)
  (install-info-labels!)
  (start-location!))

(defn restart!
  "Tears down all state and rebuilds from scratch.
  Safe to call multiple times from the nREPL while iterating on UI code."
  []
  (stop-location!)
  (reset! last-location nil)
  (reset! app-state {:tab-bar nil :map-nav nil :map-vc nil
                     :info-nav nil :info-vc nil :map-view nil
                     :info-labels nil :location-mgr nil})
  (start!))
