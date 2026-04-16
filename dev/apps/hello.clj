;; apps/hello.clj — Hello World app
;;
;; Sets the status bar message in ContentView and prints to the log.
;; Self-contained: no sub-namespace dependencies.

(ns apps.hello
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation     :as f]
            [grease.ios.objc           :as objc-rt]))

(defn- hook []
  (objc-rt/msg-send :pointer (grease/get-objc-class "GreaseHook") "shared"))

(println "[hello] loaded")

(grease/dispatch-main-async
  (fn []
    (objc-rt/msg-send :void (hook) "setMessage:"
                      :pointer (f/->nsstring "Hello from Grease!"))))
