;; dev/appstore.clj — Grease App Store
;;
;; Fetches apps/manifest.edn from base-url, presents a UIAlertController
;; listing each app, and loads the selected app via load-file.
;;
;; Load from nREPL:
;;   scripts/repl-eval "(load-file \"http://192.168.0.102:8000/appstore.clj\")"
;;
;; Load via URL scheme (from Safari):
;;   grease://load?url=http%3A%2F%2F192.168.0.102%3A8000%2Fappstore.clj
;;
;; Override server URL if needed:
;;   (reset! grease.appstore/base-url "http://other-host:8000")

(ns grease.appstore
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.blocks         :as blk]
            [grease.ios.foundation     :as f]
            [grease.ios.objc           :as objc-rt]))

(def base-url
  "Atom holding the HTTP server base URL. Reset from the nREPL to change."
  (atom "http://192.168.0.102:8000"))

(defonce ^:private held-blocks (atom []))

(defn- get-class [n] (grease/get-objc-class n))

(defn- fetch-manifest []
  ;; load-file fetches and evaluates the URL. EDN is valid Clojure, so a
  ;; vector literal in manifest.edn evaluates directly to a Clojure vector.
  (load-file (str @base-url "/apps/manifest.edn")))

(defn show!
  "Fetch the manifest and present the app store as a UIAlertController."
  []
  (try
    (println "[appstore] fetching manifest from" @base-url)
    (let [apps (fetch-manifest)]
      (println "[appstore]" (count apps) "apps found")
      (grease/dispatch-main-async
        (fn []
          (let [alert (objc-rt/msg-send :pointer
                        (get-class "UIAlertController")
                        "alertControllerWithTitle:message:preferredStyle:"
                        :pointer (f/->nsstring "Grease App Store")
                        :pointer (f/->nsstring (str (count apps) " apps available"))
                        :int64 1)] ; UIAlertControllerStyleActionSheet
            ;; One action per app
            (doseq [{:keys [name description file]} apps]
              (let [url   (str @base-url "/" file)
                    block (blk/make-typed-block
                            :void [:pointer]
                            (fn [_]
                              (println (str "[appstore] installing: " name))
                              (future
                                ;; Stop the current app (halts its tick loops, etc.)
                                ;; before we load the new one.
                                (try (when-let [f (resolve 'grease.shell/stop-current-app!)] (f))
                                     (catch Exception _))
                                ;; Brief pause for the dismiss animation to complete
                                ;; before the new app replaces the root VC.
                                (objc-rt/msg-send :void
                                  (get-class "NSThread")
                                  "sleepForTimeInterval:" :float64 0.35)
                                (load-file url)
                                ;; Re-raise the shell button above the new root VC view.
                                (try (when-let [f (resolve 'grease.shell/bring-to-front!)] (f))
                                     (catch Exception _)))))
                    action (objc-rt/msg-send :pointer (get-class "UIAlertAction")
                             "actionWithTitle:style:handler:"
                             :pointer (f/->nsstring (str name " — " description))
                             :int64 0  ; UIAlertActionStyleDefault
                             :pointer block)]
                (swap! held-blocks conj block)
                (objc-rt/msg-send :void alert "addAction:" :pointer action)))
            ;; Cancel
            (let [cancel (objc-rt/msg-send :pointer (get-class "UIAlertAction")
                           "actionWithTitle:style:handler:"
                           :pointer (f/->nsstring "Cancel")
                           :int64 1   ; UIAlertActionStyleCancel
                           :pointer (f/null-ptr))]
              (objc-rt/msg-send :void alert "addAction:" :pointer cancel))
            ;; Present from root VC
            (let [hook (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared")
                  win  (objc-rt/msg-send :pointer hook "window")
                  rvc  (objc-rt/msg-send :pointer win "rootViewController")]
              (objc-rt/msg-send :void rvc
                "presentViewController:animated:completion:"
                :pointer alert :int8 1 :pointer (f/null-ptr)))))))
    (catch Exception e
      (println (str "[appstore] error: " (.getMessage e))))))

(println "[appstore] loaded — call (grease.appstore/show!) to open")
(show!)
