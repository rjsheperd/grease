;; dev/demo_app/info.clj — Info tab label management for the demo app.
;;
;; install! creates the label stack, installs it into the given view controller's
;; view, and returns a labels map {:lat-lbl :lng-lbl :acc-lbl}.
;; update! takes that labels map and a CLLocation pointer, refreshing text.

(ns demo-app.info
  (:require [com.phronemophobic.grease     :as grease]
            [demo-app.location             :as loc]
            [demo-app.map                  :as m]
            [grease.ios.foundation         :as f]
            [grease.ios.objc               :as objc-rt]
            [grease.ios.uikit              :as ui]
            [com.phronemophobic.grease     :as grease]
            [grease.ios.repl               :refer [on-main]]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Label factory
;; ─────────────────────────────────────────────────────────────────────────────

(defn- get-class [n] (grease/get-objc-class n))

(defn- make-label!
  "Creates a centered UILabel at position [20 y] with full-width minus margins."
  [text y]
  (let [lbl (objc-rt/msg-send :pointer (get-class "UILabel") "new")
        w   (- (m/screen-width) 40.0)]
    (ui/set-frame! lbl 20.0 y w 44.0)
    (objc-rt/msg-send :void lbl "setText:"
                      :pointer (f/->nsstring text))
    (objc-rt/msg-send :void lbl "setTextAlignment:"
                      :int64 1) ; NSTextAlignmentCenter
    (objc-rt/msg-send :void lbl "setFont:"
                      :pointer (objc-rt/msg-send :pointer (get-class "UIFont")
                                                 "systemFontOfSize:" :float64 16.0))
    lbl))

;; ─────────────────────────────────────────────────────────────────────────────
;; Public API
;; ─────────────────────────────────────────────────────────────────────────────

(defn install!
  "Builds the lat/lng/accuracy label stack inside info-vc's root view.
  Returns a labels map {:lat-lbl :lng-lbl :acc-lbl} for use with update!."
  [info-vc]
  (on-main
   (let [root-v  (objc-rt/msg-send :pointer info-vc "view")
         lat-lbl (make-label! "Lat: waiting for GPS…" 140.0)
         lng-lbl (make-label! "Lng: waiting for GPS…" 192.0)
         acc-lbl (make-label! "Accuracy: —"           244.0)]
     (doseq [l [lat-lbl lng-lbl acc-lbl]]
       (objc-rt/msg-send :void root-v "addSubview:" :pointer l))
     {:lat-lbl lat-lbl
      :lng-lbl lng-lbl
      :acc-lbl acc-lbl})))

(defn update!
  "Refreshes the Info tab labels from a CLLocation pointer.
  labels is the map returned by install!. Safe to call from any thread,
  including the main thread — dispatches asynchronously so it never blocks."
  [labels loc]
  (when (and labels loc)
    (let [{:keys [lat-lbl lng-lbl acc-lbl]} labels
          lat-v (loc/latitude  loc)
          lng-v (loc/longitude loc)
          acc-v (loc/accuracy  loc)]
      (grease/dispatch-main-async
       (fn []
         (objc-rt/msg-send :void lat-lbl "setText:"
                           :pointer (f/->nsstring (format "Lat: %.6f" lat-v)))
         (objc-rt/msg-send :void lng-lbl "setText:"
                           :pointer (f/->nsstring (format "Lng: %.6f" lng-v)))
         (objc-rt/msg-send :void acc-lbl "setText:"
                           :pointer (f/->nsstring (format "Accuracy: ±%.0fm" acc-v))))))))
