(ns grease.ios.uikit
  "UIKit view hierarchy helpers for live iOS manipulation from the nREPL.

  All functions that touch UIKit state must run on the main thread.  Wrap
  calls with [[grease.ios.repl/on-main]] when invoking from the nREPL.

  Quick reference:

    ;; Navigate the view hierarchy
    (on-main (key-window))            ;; => UIWindow pointer
    (on-main (root-view))             ;; => root UIViewController's view
    (on-main (subviews (root-view)))  ;; => vec of UIView pointers
    (on-main (describe-view (root-view))) ;; => {:class ... :frame ...}

    ;; Read / write frames (no CGRect struct marshaling needed)
    (on-main (get-frame (root-view)))                  ;; => {:x 0.0 :y 0.0 ...}
    (on-main (set-frame! some-view 0 100 300 44))

    ;; Create and place UI elements
    (on-main
      (let [lbl (new-label \"Hello from REPL\")]
        (add-subview! (root-view) lbl)))"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; View hierarchy traversal
;; =============================================================================

(defn key-window
  "Returns the application's key UIWindow pointer, or nil."
  []
  (let [app (objc-rt/msg-send :pointer
                              (grease/get-objc-class "UIApplication")
                              "sharedApplication")]
    (objc-rt/msg-send :pointer app "keyWindow")))

(defn root-view
  "Returns the root view controller's view."
  []
  (let [win (key-window)
        rvc (objc-rt/msg-send :pointer win "rootViewController")]
    (objc-rt/msg-send :pointer rvc "view")))

(defn subviews
  "Returns a Clojure vec of UIView pointers that are direct subviews of view."
  [view]
  (f/nsarray->vec (objc-rt/msg-send :pointer view "subviews")))

(defn describe-view
  "Returns a map describing view: its ObjC class name, frame, and subview count."
  [view]
  {:class (f/nsstring->str
           (objc-rt/msg-send :pointer
                             (objc-rt/msg-send :pointer view "class")
                             "description"))
   :frame {:x (ffi/call "grease_get_frame_x" :float64 :pointer view)
           :y (ffi/call "grease_get_frame_y" :float64 :pointer view)
           :w (ffi/call "grease_get_frame_w" :float64 :pointer view)
           :h (ffi/call "grease_get_frame_h" :float64 :pointer view)}
   :subview-count (count (subviews view))})

;; =============================================================================
;; Frame / geometry — uses C shims to avoid CGRect struct marshaling
;; =============================================================================

(defn get-frame
  "Returns the frame of view as a map {:x :y :w :h}.
  Must be called on the main thread."
  [view]
  {:x (ffi/call "grease_get_frame_x" :float64 :pointer view)
   :y (ffi/call "grease_get_frame_y" :float64 :pointer view)
   :w (ffi/call "grease_get_frame_w" :float64 :pointer view)
   :h (ffi/call "grease_get_frame_h" :float64 :pointer view)})

(defn set-frame!
  "Sets the frame of view to {x y w h}. Must be called on the main thread."
  [view x y w h]
  (ffi/call "grease_set_frame" :void
            :pointer view
            :float64 (double x)
            :float64 (double y)
            :float64 (double w)
            :float64 (double h)))

(defn get-center
  "Returns the center of view as {:cx :cy}. Must be on main thread."
  [view]
  {:cx (ffi/call "grease_get_center_x" :float64 :pointer view)
   :cy (ffi/call "grease_get_center_y" :float64 :pointer view)})

(defn set-center!
  "Sets the center of view to {cx cy}. Must be called on the main thread."
  [view cx cy]
  (ffi/call "grease_set_center" :void
            :pointer view
            :float64 (double cx)
            :float64 (double cy)))

;; =============================================================================
;; UI element creation helpers
;; =============================================================================

(defn new-label
  "Creates a UILabel with the given text string."
  [text]
  (let [lbl (objc-rt/msg-send :pointer
                              (grease/get-objc-class "UILabel")
                              "new")]
    (objc-rt/msg-send :void lbl "setText:" :pointer (f/->nsstring text))
    lbl))

(defn add-subview!
  "Adds child as a subview of parent. Must be called on the main thread."
  [parent child]
  (objc-rt/msg-send :void parent "addSubview:" :pointer child))

(defn remove-from-superview!
  "Removes view from its superview. Must be called on the main thread."
  [view]
  (objc-rt/msg-send :void view "removeFromSuperview"))
