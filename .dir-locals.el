;;; Directory Local Variables — grease (Clojure-on-iOS)
;;; For more information see (info "(emacs) Directory Variables")

((nil
  . (;; Pre-populated endpoints for M-x cider-connect.
     ;; The device entry appears first, making it the default selection.
     ;; Start the app before connecting:
     ;;   device:    ./scripts/debug-demo-app --noinstall
     ;;   simulator: ./scripts/sim-run
     (cider-known-endpoints . (("iOS Device"    "192.168.0.111" "23456")
                               ("iOS Simulator" "localhost"     "23456")))

     ;; Use clojure-cli and the :repl/device alias when jack-in is used
     ;; instead of cider-connect (e.g. for inspector or macro-expand tooling).
     (cider-preferred-build-tool . clojure-cli)
     (cider-clojure-cli-aliases . ":repl/device")

     ;; The on-device nREPL runs babashka.nrepl (SCI), not a JVM nREPL, so
     ;; cider-nrepl middleware cannot be loaded. Disable injection entirely so
     ;; CIDER does not try to add it to the classpath or start command, and
     ;; clear the middleware list so no nREPL middleware is requested at all.
     (cider-inject-dependencies-at-jack-in . nil)
     (cider-jack-in-nrepl-middlewares . nil))))
