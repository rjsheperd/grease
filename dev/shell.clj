;; dev/shell.clj — Grease App Shell
;;
;; Installs a persistent λ button directly on UIWindow.
;; Window-level subviews survive setRootViewController: calls, so the
;; button remains visible regardless of which app is currently running.
;;
;; Usage:
;;   (load-file "http://192.168.0.102:8000/shell.clj")
;;   ;; λ button appears in bottom-right corner — tap to open the app store

(ns grease.shell
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation     :as f]
            [grease.ios.objc           :as objc-rt]
            [grease.ios.uikit          :as ui]))

(def base-url
  "Atom holding the HTTP server base URL. Override if needed."
  (atom "http://192.168.0.102:8000"))

(defonce ^:private state
  (atom {:button nil :handler nil :current-url nil :stop! nil}))

;; Indirection atom so the tap handler can call show! before it is defined.
(defonce ^:private on-tap-fn (atom nil))

(defn- get-class [n] (grease/get-objc-class n))

;; ObjC class to receive UIButton tap callbacks.
;; defonce prevents double-registration if shell.clj is reloaded mid-session.
#_{:clj-kondo/ignore [:inline-def]}
(defonce _shell-btn-handler
  (grease.ios.objc/defclass ShellBtnHandler "NSObject"
    "tapped:" "v@:@"
    (fn [_self _cmd _sender]
      (when-let [f @on-tap-fn]
        (grease/dispatch-main-async f)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Public API
;; ─────────────────────────────────────────────────────────────────────────────

(defn current-app
  "Returns the URL of the currently installed app, or nil."
  []
  (:current-url @state))

(defn register-stop!
  "Called by an app after it starts, to register its stop! fn with the shell.
  The shell calls this fn before loading a new app."
  [f]
  (swap! state assoc :stop! f))

(defn stop-current-app!
  "Invoke the registered stop! fn for the currently running app, if any."
  []
  (when-let [f (:stop! @state)]
    (f))
  (swap! state assoc :stop! nil :current-url nil))

(defn register-app!
  "Called (optionally) by apps to let the shell track what is running.
  opts: {:url string :stop! fn}"
  [{:keys [url]}]
  (swap! state assoc :current-url url))

(defn bring-to-front!
  "Ensures the λ button is above the current root VC's view.
  Call this after any setRootViewController: to keep the button visible."
  []
  (when-let [btn (:button @state)]
    (grease/dispatch-main-async
      (fn []
        (let [hook (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared")
              win  (objc-rt/msg-send :pointer hook "window")]
          (objc-rt/msg-send :void win "bringSubviewToFront:" :pointer btn))))))

(defn stop!
  "Remove the shell button and reset state."
  []
  (when-let [btn (:button @state)]
    (grease/dispatch-main-async #(ui/remove-from-superview! btn)))
  (reset! state {:button nil :handler nil :current-url nil}))

(defn start!
  "Load the app store and install the persistent λ button on UIWindow."
  []
  (stop!)
  ;; Load the app store namespace so show! is available.
  (load-file (str @base-url "/appstore.clj"))
  ;; Wire the button tap to the store.
  ;; Use resolve so SCI doesn't try to look up grease.appstore at compile time.
  (reset! on-tap-fn (fn []
    (when-let [f (resolve 'grease.appstore/show!)]
      (f))))
  ;; Build the floating button on the main thread.
  (grease/dispatch-main-async
    (fn []
      (let [hook    (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared")
            win     (objc-rt/msg-send :pointer hook "window")
            {:keys [w h]} (ui/get-frame win)
            btn     (objc-rt/msg-send :pointer (get-class "UIButton")
                                      "buttonWithType:" :int64 0)
            handler (objc-rt/new-instance ShellBtnHandler)]
        ;; Position: bottom-right, above home indicator
        (ui/set-frame! btn (- w 68.0) (- h 108.0) 52.0 52.0)
        ;; Flexible left+top margins so it stays pinned to bottom-right on rotation
        ;; UIViewAutoresizingFlexibleLeftMargin=1 | FlexibleTopMargin=8 = 9
        (objc-rt/msg-send :void btn "setAutoresizingMask:" :int64 9)
        ;; Circular shape via CALayer
        (let [layer (objc-rt/msg-send :pointer btn "layer")]
          (objc-rt/msg-send :void layer "setCornerRadius:" :float64 26.0)
          (objc-rt/msg-send :void layer "setMasksToBounds:" :int8 1))
        ;; Clojure teal background, semi-transparent
        (ui/set-background-color! btn 0.36 0.78 0.78 0.88)
        ;; λ label
        (objc-rt/msg-send :void btn "setTitle:forState:"
                          :pointer (f/->nsstring "λ") :int64 0)
        (let [lbl  (objc-rt/msg-send :pointer btn "titleLabel")
              font (objc-rt/msg-send :pointer (get-class "UIFont")
                                     "boldSystemFontOfSize:" :float64 22.0)]
          (objc-rt/msg-send :void lbl "setFont:" :pointer font))
        ;; Wire tap: UIControlEventTouchUpInside = 1<<6 = 64
        (objc-rt/msg-send :void btn "addTarget:action:forControlEvents:"
                          :pointer handler
                          :pointer (grease/register-objc-sel "tapped:")
                          :int64 64)
        ;; Add to window — sits above root VC view
        (objc-rt/msg-send :void win "addSubview:" :pointer btn)
        (swap! state assoc :button btn :handler handler))))
  (println "[shell] started — λ button added to window"))

(println "[shell] loaded — call (grease.shell/start!) to install")
(start!)
