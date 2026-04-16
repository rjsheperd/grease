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

    ;; Read / write frames
    (on-main (get-frame (root-view)))                  ;; => {:x 0.0 :y 0.0 ...}
    (on-main (set-frame! some-view 0 100 300 44))

    ;; Create and place UI elements
    (on-main
      (let [lbl (new-label \"Hello from REPL\")]
        (add-subview! (root-view) lbl)))"
  (:require [com.phronemophobic.grease :as grease]
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

;; =============================================================================
;; Frame / geometry — struct-return/arg dispatch via ffi/call-ptr
;; =============================================================================

(def ^:private frame-get-spec
  {:selector "frame"
   :encoding "{CGRect={CGPoint=dd}{CGSize=dd}}@:"
   :args     []
   :return   "CGRect"})

(defn get-frame
  "Returns the frame of view as {:x :y :w :h}.
  Must be called on the main thread."
  [view]
  (let [r ((requiring-resolve 'grease.ios.invoke/dispatch!)
           view "frame" frame-get-spec [])]
    {:x (get (get r :origin) :x)
     :y (get (get r :origin) :y)
     :w (get (get r :size) :width)
     :h (get (get r :size) :height)}))

(def ^:private set-frame-spec
  {:selector "setFrame:"
   :encoding "v@:{CGRect={CGPoint=dd}{CGSize=dd}}"
   :args     [{:name "frame" :type "CGRect"}]
   :return   "void"})

(defn set-frame!
  "Sets the frame of view to {x y w h}. Must be called on the main thread."
  [view x y w h]
  ((requiring-resolve 'grease.ios.invoke/dispatch!)
   view "setFrame:" set-frame-spec
   [{:origin {:x (double x) :y (double y)}
     :size   {:width (double w) :height (double h)}}]))

(def ^:private center-get-spec
  {:selector "center"
   :encoding "{CGPoint=dd}@:"
   :args     []
   :return   "CGPoint"})

(defn get-center
  "Returns the center of view as {:cx :cy}. Must be on main thread."
  [view]
  (let [pt ((requiring-resolve 'grease.ios.invoke/dispatch!)
            view "center" center-get-spec [])]
    {:cx (get pt :x) :cy (get pt :y)}))

(def ^:private set-center-spec
  {:selector "setCenter:"
   :encoding "v@:{CGPoint=dd}"
   :args     [{:name "center" :type "CGPoint"}]
   :return   "void"})

(defn set-center!
  "Sets the center of view to {cx cy}. Must be called on the main thread."
  [view cx cy]
  ((requiring-resolve 'grease.ios.invoke/dispatch!)
   view "setCenter:" set-center-spec
   [{:x (double cx) :y (double cy)}]))

(defn describe-view
  "Returns a map describing view: its ObjC class name, frame, and subview count."
  [view]
  {:class (f/nsstring->str
           (objc-rt/msg-send :pointer
                             (objc-rt/msg-send :pointer view "class")
                             "description"))
   :frame (get-frame view)
   :subview-count (count (subviews view))})

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

;; =============================================================================
;; Styling helpers
;; =============================================================================

(defn- make-color
  "Creates a UIColor from RGBA components (0.0–1.0 each)."
  [r g b a]
  (objc-rt/msg-send :pointer
                    (grease/get-objc-class "UIColor")
                    "colorWithRed:green:blue:alpha:"
                    :float64 (double r)
                    :float64 (double g)
                    :float64 (double b)
                    :float64 (double a)))

(defn set-text-color!
  "Sets the text color of label. r g b a are 0.0–1.0.
  Must be called on the main thread."
  [label r g b a]
  (objc-rt/msg-send :void label "setTextColor:" :pointer (make-color r g b a)))

(defn set-background-color!
  "Sets the background color of view. r g b a are 0.0–1.0.
  Must be called on the main thread."
  [view r g b a]
  (objc-rt/msg-send :void view "setBackgroundColor:" :pointer (make-color r g b a)))

(defn set-font-size!
  "Sets the font size of label, preserving the current font family.
  Must be called on the main thread."
  [label size]
  (let [current-font (objc-rt/msg-send :pointer label "font")
        font-name    (objc-rt/msg-send :pointer current-font "fontName")
        new-font     (objc-rt/msg-send :pointer
                                       (grease/get-objc-class "UIFont")
                                       "fontWithName:size:"
                                       :pointer font-name
                                       :float64 (double size))]
    (objc-rt/msg-send :void label "setFont:" :pointer new-font)))

(defn set-text-alignment!
  "Sets the text alignment of label.
  alignment: 0=left 1=center 2=right 3=justified 4=natural.
  Must be called on the main thread."
  [label alignment]
  (objc-rt/msg-send :void label "setTextAlignment:" :int64 (long alignment)))

;; =============================================================================
;; View hierarchy walker
;; =============================================================================

(defn view-tree
  "Returns a nested map {:class :frame :subview-count :children} for the
  view hierarchy rooted at view. Depth controls recursion limit (default 4)."
  ([view] (view-tree view 4))
  ([view depth]
   (let [info (describe-view view)]
     (if (pos? depth)
       (assoc info :children (mapv #(view-tree % (dec depth)) (subviews view)))
       info))))
