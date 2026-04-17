(ns calc-app
  "Scientific calculator app entry point.

  Uses GSL (via Bridge/GSL.m) for special-function buttons (Γ, erf, J₀, ln, ζ, eˣ).
  Basic arithmetic (+  −  ×  ÷) is pure Clojure.

  Load order (via scripts/repl-eval):
    1. dev/calc_app/gsl.clj
    2. dev/calc_app/logic.clj
    3. dev/calc_app/ui.clj
    4. dev/calc_app.clj

  Then:  (calc-app/start!)
  Reset: (calc-app/stop!) then reload and (calc-app/start!) again."
  (:require [grease.ios.objc :as objc-rt]
            [com.phronemophobic.grease :as grease]
            [calc-app.logic :as logic]
            [calc-app.ui :as calc-ui]))

(defonce ^:private app-state (atom {:root-vc nil}))

(defn start!
  "Present a new UIViewController and install the calculator UI into it."
  []
  (grease/dispatch-main-async
   (fn []
     (let [root-vc (objc-rt/msg-send :pointer
                                     (grease/get-objc-class "UIViewController")
                                     "new")
           window  (GreaseHook/window)
           top-vc  (objc-rt/msg-send :pointer window "rootViewController")]
       (objc-rt/msg-send :void top-vc
                         "presentViewController:animated:completion:"
                         :pointer root-vc :sint32 1 :pointer nil)
       (swap! app-state assoc :root-vc root-vc)
       (calc-ui/install! root-vc)))))

(defn stop!
  "Remove watches and clear state. The presented view controller remains on screen;
  dismiss it manually or call start! again after reloading namespaces."
  []
  (calc-ui/uninstall!)
  (logic/press-clear!))
