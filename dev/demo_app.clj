;; dev/demo_app.clj — Map + Tab Bar demo app (top-level coordinator)
;;
;; Builds a UITabBarController with two tabs:
;;   Tab 0 — Map:  MKMapView, blue user-location dot, auto-follows GPS fix
;;   Tab 1 — Info: UILabel stack showing lat/lng/accuracy live from CLLocation
;;
;; Sub-namespaces:
;;   demo-app.map      — MKMapView helpers and screen-size accessors
;;   demo-app.location — CLLocationManager, @last-location atom
;;   demo-app.info     — Info tab label factory and updater
;;   demo-app.reload   — restart! hot-reload helper
;;
;; Usage from nREPL after deploy:
;;   (load-file "dev/demo_app/map.clj")
;;   (load-file "dev/demo_app/location.clj")
;;   (load-file "dev/demo_app/info.clj")
;;   (load-file "dev/demo_app.clj")
;;   (demo-app/start!)
;;
;; Hot-reload:
;;   (load-file "dev/demo_app/reload.clj")
;;   (demo-app.reload/restart!)

(ns demo-app
  (:require [com.phronemophobic.grease :as grease]
            [demo-app.info             :as info]
            [demo-app.location         :as loc]
            [demo-app.map              :as m]
            [grease.ios.foundation     :as f]
            [grease.ios.objc           :as objc-rt]
            [grease.ios.uikit          :as ui]
            [grease.ios.repl           :refer [on-main]]))

;; ─────────────────────────────────────────────────────────────────────────────
;; App state — all live ObjC pointers retained here to prevent ARC collection.
;; Keys: :tab-bar :map-nav :map-vc :info-nav :info-vc :map-view :info-labels
;; ─────────────────────────────────────────────────────────────────────────────

(defonce app-state
  (atom {:tab-bar     nil
         :map-nav     nil
         :map-vc      nil
         :info-nav    nil
         :info-vc     nil
         :map-view    nil
         :info-labels nil}))

(defn reset-state!
  "Resets app-state to blank. Call before rebuilding the UI."
  []
  (reset! app-state {:tab-bar nil :map-nav nil :map-vc nil
                     :info-nav nil :info-vc nil :map-view nil
                     :info-labels nil}))

;; ─────────────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn- get-class [n] (grease/get-objc-class n))
(defn- new-instance [n] (objc-rt/msg-send :pointer (get-class n) "new"))

;; ─────────────────────────────────────────────────────────────────────────────
;; Tab bar construction
;; ─────────────────────────────────────────────────────────────────────────────

(defn- make-tab-item!
  "Creates a UITabBarItem with a text title, no image, and the given tag."
  [title tag]
  (objc-rt/msg-send :pointer (get-class "UITabBarItem")
                    "initWithTitle:image:tag:"
                    :pointer (f/->nsstring title)
                    :pointer (f/null-ptr)
                    :int64   tag))

(defn- make-plain-vc!
  "Allocates a UIViewController with a solid system background color."
  [bg-selector]
  (let [vc (new-instance "UIViewController")
        v  (objc-rt/msg-send :pointer vc "view")]
    (objc-rt/msg-send :void v "setBackgroundColor:"
                      :pointer (objc-rt/msg-send :pointer (get-class "UIColor")
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

(defn install-map-view!
  "Creates a full-screen MKMapView in the Map tab, enables the blue
  user-location dot, and centers on Apple HQ as a placeholder."
  []
  (on-main
   (let [map-vc (:map-vc @app-state)
         root-v (objc-rt/msg-send :pointer map-vc "view")
         map-v  (new-instance "MKMapView")
         w      (m/screen-width)
         h      (m/screen-height)]
     (ui/set-frame! map-v 0.0 0.0 w h)
     ;; UIViewAutoresizingFlexibleWidth | FlexibleHeight = 2 | 16 = 18
     (objc-rt/msg-send :void map-v "setAutoresizingMask:" :int64 18)
     (objc-rt/msg-send :void map-v "setShowsUserLocation:" :int8 1)
     (m/set-region! map-v m/apple-hq-lat m/apple-hq-lng
                    m/default-span m/default-span false)
     (objc-rt/msg-send :void root-v "addSubview:" :pointer map-v)
     (swap! app-state assoc :map-view map-v))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Location → map + info wiring
;; ─────────────────────────────────────────────────────────────────────────────

(defn- enable-map-follow!
  "Switches the MKMapView to MKUserTrackingModeFollow on first GPS fix."
  []
  (when-let [mv (:map-view @app-state)]
    (on-main
     (objc-rt/msg-send :void mv "setUserTrackingMode:animated:"
                       :int64 1 :int8 1))))

(defn- on-location
  "Called by demo-app.location/start! with each new CLLocation pointer."
  [loc]
  (when (nil? @loc/last-location) (enable-map-follow!))
  (info/update! (:info-labels @app-state) loc))

;; ─────────────────────────────────────────────────────────────────────────────
;; Top-level entry points
;; ─────────────────────────────────────────────────────────────────────────────

(defn start!
  "Full app setup in one shot:
  1. Builds the tab bar shell.
  2. Adds MKMapView to the Map tab.
  3. Adds the label stack to the Info tab; stores labels in @app-state.
  4. Starts CLLocationManager with the on-location callback."
  []
  (install-tab-bar!)
  (install-map-view!)
  (let [labels (info/install! (:info-vc @app-state))]
    (swap! app-state assoc :info-labels labels))
  (loc/start! on-location))
