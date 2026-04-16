;; apps/colours.clj — Colour Pad app
;;
;; Full-screen view that cycles through colours on each tap.
;; Uses defclass to wire a UITapGestureRecognizer target-action.
;; Call (apps.colours/stop!) to dismiss before loading another app.

(ns apps.colours
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation     :as f]
            [grease.ios.objc           :as objc-rt]
            [grease.ios.uikit          :as ui]))

(defonce ^:private state (atom {:idx 0 :vc nil :view nil :tapper nil}))

(def ^:private palette
  [[0.95 0.26 0.21]   ; red
   [1.00 0.60 0.00]   ; amber
   [0.13 0.59 0.95]   ; blue
   [0.30 0.69 0.31]   ; green
   [0.60 0.27 0.81]   ; purple
   [0.00 0.74 0.83]   ; cyan
   [0.91 0.12 0.39]   ; pink
   [1.00 0.76 0.03]]) ; yellow

(defn- get-class [n] (grease/get-objc-class n))

(defn- apply-color! []
  (when-let [v (:view @state)]
    (let [[r g b] (nth palette (mod (:idx @state) (count palette)))]
      (ui/set-background-color! v r g b 1.0))))

;; ObjC class to receive tap gesture callbacks.
;; defonce wraps the defclass so the ObjC class is only registered once per
;; app session — reloading colours.clj will not attempt a second registration.
#_{:clj-kondo/ignore [:inline-def]}
(defonce _colour-tap-handler
  (grease.ios.objc/defclass ColourTapHandler "NSObject"
    "handleTap:" "v@:@"
    (fn [_self _cmd _sender]
      (swap! state update :idx inc)
      (grease/dispatch-main-async apply-color!))))

(defn stop!
  "Dismiss the colour pad and release state."
  []
  (when-let [vc (:vc @state)]
    (grease/dispatch-main-async
      (fn []
        (objc-rt/msg-send :void vc "dismissViewControllerAnimated:completion:"
                          :int8 1 :pointer (f/null-ptr)))))
  (reset! state {:idx 0 :vc nil :view nil :tapper nil}))

(defn- build-ui! []
  (let [vc      (objc-rt/msg-send :pointer (get-class "UIViewController") "new")
        view    (objc-rt/msg-send :pointer vc "view")
        tapper  (objc-rt/new-instance ColourTapHandler)
        gr      (objc-rt/msg-send :pointer
                  (objc-rt/msg-send :pointer (get-class "UITapGestureRecognizer") "alloc")
                  "initWithTarget:action:"
                  :pointer tapper
                  :pointer (grease/register-objc-sel "handleTap:"))
        label   (objc-rt/msg-send :pointer (get-class "UILabel") "new")
        hook    (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared")
        win     (objc-rt/msg-send :pointer hook "window")
        {:keys [w h]} (ui/get-frame win)]
    ;; Hint label
    (objc-rt/msg-send :void label "setText:"
                      :pointer (f/->nsstring "Tap to change colour"))
    (let [font (objc-rt/msg-send :pointer (get-class "UIFont")
                                 "systemFontOfSize:weight:"
                                 :float64 24.0 :float64 0.0)]
      (objc-rt/msg-send :void label "setFont:" :pointer font))
    (ui/set-text-color! label 1.0 1.0 1.0 0.85)
    (ui/set-text-alignment! label 1)
    (ui/set-frame! label 0.0 (- (/ h 2.0) 20.0) w 44.0)
    (ui/add-subview! view label)
    (objc-rt/msg-send :void view "addGestureRecognizer:" :pointer gr)
    ;; Install as root VC
    (objc-rt/msg-send :void win "setRootViewController:" :pointer vc)
    {:vc vc :view view :tapper tapper}))

(defn start!
  "Build the colour pad UI."
  []
  (stop!)
  (grease/dispatch-main-async
    (fn []
      (let [{:keys [vc view tapper]} (build-ui!)]
        (swap! state assoc :vc vc :view view :tapper tapper)
        (apply-color!)))))

(println "[colours] loaded — calling start!")
(start!)
(try (when-let [f (resolve 'grease.shell/register-stop!)] (f stop!))
     (catch Exception _))
