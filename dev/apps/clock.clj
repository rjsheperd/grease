;; apps/clock.clj — Live Clock app
;;
;; Displays a full-screen digital clock that ticks every second.
;; Uses a background future + dispatch-main-async to update the label.
;; NSThread sleepForTimeInterval: is used instead of Thread/sleep (not in SCI).
;; Call (apps.clock/stop!) to halt before loading another app.

(ns apps.clock
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation     :as f]
            [grease.ios.objc           :as objc-rt]
            [grease.ios.uikit          :as ui]))

(defonce ^:private state (atom {:running? false :vc nil :label nil}))

(defn- get-class [n] (grease/get-objc-class n))

(defn- sleep-s [seconds]
  (objc-rt/msg-send :void (get-class "NSThread")
                    "sleepForTimeInterval:" :float64 (double seconds)))

(defn stop!
  "Stop the clock tick loop and remove the clock view controller."
  []
  (swap! state assoc :running? false)
  (when-let [vc (:vc @state)]
    (grease/dispatch-main-async
      (fn []
        (objc-rt/msg-send :void vc "dismissViewControllerAnimated:completion:"
                          :int8 1 :pointer (f/null-ptr)))))
  (swap! state assoc :vc nil :label nil))

(defn- current-time-str []
  (let [fmt (objc-rt/msg-send :pointer (get-class "NSDateFormatter") "new")]
    (objc-rt/msg-send :void fmt "setDateFormat:"
                      :pointer (f/->nsstring "HH:mm:ss"))
    (f/nsstring->str
      (objc-rt/msg-send :pointer fmt "stringFromDate:"
                        :pointer (objc-rt/msg-send :pointer (get-class "NSDate") "date")))))

(defn- build-ui! []
  (let [hook  (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared")
        win   (objc-rt/msg-send :pointer hook "window")
        {:keys [w h]} (ui/get-frame win)
        vc    (objc-rt/msg-send :pointer (get-class "UIViewController") "new")
        view  (objc-rt/msg-send :pointer vc "view")
        label (objc-rt/msg-send :pointer (get-class "UILabel") "new")]
    (ui/set-background-color! view 0.0 0.0 0.0 1.0)
    (objc-rt/msg-send :void label "setText:"
                      :pointer (f/->nsstring (current-time-str)))
    (let [font (objc-rt/msg-send :pointer (get-class "UIFont")
                                 "monospacedDigitSystemFontOfSize:weight:"
                                 :float64 72.0 :float64 0.0)]
      (objc-rt/msg-send :void label "setFont:" :pointer font))
    (ui/set-text-color! label 1.0 1.0 1.0 1.0)
    (ui/set-text-alignment! label 1)
    (objc-rt/msg-send :void label "setAdjustsFontSizeToFitWidth:" :int8 1)
    (ui/set-frame! label 20.0 (- (/ h 2.0) 50.0) (- w 40.0) 100.0)
    (ui/add-subview! view label)
    (objc-rt/msg-send :void win "setRootViewController:" :pointer vc)
    {:vc vc :label label}))

(defn start!
  "Build the clock UI and start the tick loop."
  []
  (stop!)
  (swap! state assoc :running? true)
  (grease/dispatch-main-async
    (fn []
      (let [{:keys [vc label]} (build-ui!)]
        (swap! state assoc :vc vc :label label))))
  (future
    (sleep-s 0.5)
    (while (:running? @state)
      (let [t (current-time-str)]
        (grease/dispatch-main-async
          (fn []
            (when-let [lbl (:label @state)]
              (objc-rt/msg-send :void lbl "setText:" :pointer (f/->nsstring t))))))
      (sleep-s 1.0))))

(println "[clock] loaded — calling start!")
(start!)
(try (when-let [f (resolve 'grease.shell/register-stop!)] (f stop!))
     (catch Exception _))
