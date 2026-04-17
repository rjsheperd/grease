;; dev/hello_app.clj — minimal smoke-test for grease://load?url=... and (load-file "...")
;;
;; Self-contained: no sub-namespace dependencies.
;; Visible effect: sets GreaseHook message (polled by ContentView every 0.5s)
;; and prints to the in-app log.

(ns hello-app
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.objc           :as objc-rt]
            [grease.ios.foundation     :as f]))

(defn- hook []
  (objc-rt/msg-send :pointer (grease/get-objc-class "GreaseHook") "shared"))

(defn start!
  "Display a greeting in the ContentView status bar and the in-app log."
  []
  (println "[hello-app] start! called")
  (grease/dispatch-main-async
   (fn []
     (objc-rt/msg-send :void (hook) "setMessage:"
                       :pointer (f/->nsstring "Hello from hello_app.clj!")))))

(start!)
