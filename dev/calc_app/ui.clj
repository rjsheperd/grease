(ns calc-app.ui
  "UIKit layout for the calculator app.
  Builds a display label and a grid of buttons wired to calc-app.logic."
  (:require [grease.ios.uikit :as ui]
            [grease.ios.objc :as objc-rt]
            [com.phronemophobic.grease :as grease]
            [calc-app.logic :as logic]
            [calc-app.gsl :as gsl]))

;; Retained reference so the watch can update it from any thread.
(defonce ^:private display-lbl (atom nil))

;; ---------------------------------------------------------------------------
;; Colour helpers

(defn- uicolor [r g b]
  (objc-rt/msg-send :pointer
                    (grease/get-objc-class "UIColor")
                    "colorWithRed:green:blue:alpha:"
                    :float64 r :float64 g :float64 b :float64 1.0))

(def ^:private color-digit   (uicolor 0.20 0.20 0.20))
(def ^:private color-op      (uicolor 0.90 0.55 0.05))
(def ^:private color-sci     (uicolor 0.10 0.40 0.80))
(def ^:private color-clear   (uicolor 0.65 0.10 0.10))

;; ---------------------------------------------------------------------------
;; Button factory

(defn- make-btn! [label on-tap x y w h color]
  (let [btn (objc-rt/msg-send :pointer
                              (grease/get-objc-class "UIButton")
                              "buttonWithType:" :sint32 1)]
    (objc-rt/msg-send :void btn
                      "setTitle:forState:"
                      :pointer (grease/nsstring label) :sint32 0)
    (objc-rt/msg-send :void
                      (objc-rt/msg-send :pointer btn "titleLabel")
                      "setFont:"
                      :pointer (objc-rt/msg-send :pointer
                                                 (grease/get-objc-class "UIFont")
                                                 "boldSystemFontOfSize:" :float64 20.0))
    (objc-rt/msg-send :void btn "setBackgroundColor:" :pointer color)
    (ui/set-frame! btn {:x x :y y :w w :h h})
    (grease/add-action! btn on-tap)
    btn))

;; ---------------------------------------------------------------------------
;; Layout

(def ^:private btn-h   62.0)
(def ^:private btn-gap  4.0)
(def ^:private display-top 90.0)
(def ^:private display-h   80.0)
(def ^:private grid-top   180.0)

;; [label  action                 row col  color-key]
(def ^:private button-spec
  [["7"   #(logic/press-digit! "7")  0 0 :digit]
   ["8"   #(logic/press-digit! "8")  0 1 :digit]
   ["9"   #(logic/press-digit! "9")  0 2 :digit]
   ["÷"   #(logic/press-op! :div)    0 3 :op]
   ["4"   #(logic/press-digit! "4")  1 0 :digit]
   ["5"   #(logic/press-digit! "5")  1 1 :digit]
   ["6"   #(logic/press-digit! "6")  1 2 :digit]
   ["×"   #(logic/press-op! :mul)    1 3 :op]
   ["1"   #(logic/press-digit! "1")  2 0 :digit]
   ["2"   #(logic/press-digit! "2")  2 1 :digit]
   ["3"   #(logic/press-digit! "3")  2 2 :digit]
   ["-"   #(logic/press-op! :sub)    2 3 :op]
   ["0"   #(logic/press-digit! "0")  3 0 :digit]
   ["."   #(logic/press-dot!)        3 1 :digit]
   ["="   #(logic/press-equals!)     3 2 :op]
   ["+"   #(logic/press-op! :add)    3 3 :op]
   ;; Scientific row (GSL)
   ["Γ"   #(logic/press-fn! gsl/gamma)     4 0 :sci]
   ["erf" #(logic/press-fn! gsl/erf)      4 1 :sci]
   ["J₀"  #(logic/press-fn! gsl/bessel-J0) 4 2 :sci]
   ["ln"  #(logic/press-fn! gsl/ln)       4 3 :sci]
   ;; Utility row
   ["C"   #(logic/press-clear!)      5 0 :clear]
   ["xⁿ" #(logic/press-fn! (fn [x] (gsl/pow-int x 2))) 5 1 :sci]
   ["ζ"   #(logic/press-fn! gsl/zeta)     5 2 :sci]
   ["eˣ"  #(logic/press-fn! gsl/exp-gsl)  5 3 :sci]])

(defn install!
  "Build and attach calculator UI to root-vc's view.
  Installs a watch on logic/state that refreshes the display on the main thread."
  [root-vc]
  (let [root-v (objc-rt/msg-send :pointer root-vc "view")
        sw     (ui/screen-width)]

    ;; Background
    (objc-rt/msg-send :void root-v "setBackgroundColor:"
                      :pointer (uicolor 0.10 0.10 0.10))

    ;; Display label
    (let [lbl (objc-rt/msg-send :pointer
                                (grease/get-objc-class "UILabel") "new")]
      (objc-rt/msg-send :void lbl "setText:" :pointer (grease/nsstring "0"))
      (objc-rt/msg-send :void lbl "setTextAlignment:" :sint32 2) ; right
      (objc-rt/msg-send :void lbl "setTextColor:"
                        :pointer (uicolor 1.0 1.0 1.0))
      (objc-rt/msg-send :void lbl "setFont:"
                        :pointer (objc-rt/msg-send :pointer
                                                   (grease/get-objc-class "UIFont")
                                                   "monospacedDigitSystemFontOfSize:weight:"
                                                   :float64 52.0 :float64 0.0))
      (ui/set-frame! lbl {:x 12 :y display-top :w (- sw 24) :h display-h})
      (objc-rt/msg-send :void root-v "addSubview:" :pointer lbl)
      (reset! display-lbl lbl))

    ;; Buttons
    (let [bw (/ sw 4.0)]
      (doseq [[label action row col color-key] button-spec]
        (let [color  (case color-key
                       :digit color-digit
                       :op    color-op
                       :sci   color-sci
                       :clear color-clear)
              x      (* col bw)
              y      (+ grid-top (* row (+ btn-h btn-gap)))
              btn    (make-btn! label action x y bw btn-h color)]
          (objc-rt/msg-send :void root-v "addSubview:" :pointer btn))))

    ;; State watcher → refresh display label on main thread
    (add-watch logic/state ::display
               (fn [_ _ _ {:keys [display]}]
                 (grease/dispatch-main-async
                  (fn []
                    (when-let [l @display-lbl]
                      (objc-rt/msg-send :void l "setText:"
                                        :pointer (grease/nsstring display)))))))))

(defn uninstall!
  "Remove the state watcher. Call before reloading the namespace."
  []
  (remove-watch logic/state ::display))
