;; dev/demo_app/reload.clj — hot-reload helper for the demo app.
;;
;; restart! tears down all live state and rebuilds the full UI from scratch.
;; Call from the nREPL when iterating on layout or logic without a full redeploy.

(ns demo-app.reload
  (:require [demo-app.location :as loc]
            [demo-app]))

(defn restart!
  "Stops location updates, resets all atoms, and rebuilds the full UI.
  Safe to call multiple times from the nREPL."
  []
  (loc/clear!)
  (demo-app/reset-state!)
  (demo-app/start!))
