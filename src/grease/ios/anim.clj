(ns grease.ios.anim
  "UIView animations as Clojure functions and promises.

  All animation functions call `UIView animateWithDuration:…` class methods via
  `msg-send` — no new C shims required.  The animations and completion blocks
  are wrapped with the existing `make-void-block` factory.

  [[animate]] and [[spring]] return a promise delivered when the animation
  completes.  [[sequence]] chains multiple animations.  [[anim->]] is a
  threading macro that expands setter calls into an animation body fn.

  Example:
    (on-main
      (animate {:duration 0.3 :curve :ease-in-out}
        (anim-> my-view
          (alpha 0.0)
          (frame 0 100 300 44))))"
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.blocks :as blk]
            [grease.ios.color :as color]
            [grease.ios.invoke :as invoke]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; Curve keyword → UIViewAnimationOptions integer (Phase 5.5)
;; =============================================================================

(def ^:private curve-options
  "Map of curve keyword → UIViewAnimationOptions integer."
  {:ease-in-out 0x00000000
   :ease-in     0x00010000
   :ease-out    0x00020000
   :linear      0x00030000})

(defn- resolve-options
  "Converts an opts map to a UIViewAnimationOptions integer."
  [{:keys [curve] :or {curve :ease-in-out}}]
  (let [c (get curve-options curve)]
    (when-not c
      (throw (ex-info (str "Unknown animation curve: " curve)
                      {:curve curve :known (keys curve-options)})))
    c))

;; =============================================================================
;; Animatable property setters
;; =============================================================================

(defn set-alpha!
  "Sets view's alpha to `a` (0.0–1.0). Animatable."
  [view a]
  (objc-rt/msg-send :void view "setAlpha:" :float64 (double a)))

(defn set-bg!
  "Sets view's background color. `color-spec` is coerced via [[grease.ios.color/->uicolor]]."
  [view color-spec]
  (objc-rt/msg-send :void view "setBackgroundColor:" :pointer (color/->uicolor color-spec)))

(def ^:private set-frame-spec
  {:selector "setFrame:"
   :encoding "v@:{CGRect={CGPoint=dd}{CGSize=dd}}"
   :args     [{:name "frame" :type "CGRect"}]
   :return   "void"})

(defn set-frame!
  "Sets view's frame to [x y w h]. Animatable."
  [view x y w h]
  (invoke/dispatch!
   view "setFrame:" set-frame-spec
   [{:origin {:x (double x) :y (double y)}
     :size   {:width (double w) :height (double h)}}]))

;; =============================================================================
;; animate (Phase 5.1)
;; =============================================================================

(defn animate
  "Runs `body-fn` (a zero-arg fn) inside a UIView animation.

  `opts` map keys:
  - `:duration`  — seconds (required)
  - `:delay`     — seconds before starting (default 0.0)
  - `:curve`     — `:ease-in-out` (default), `:ease-in`, `:ease-out`, `:linear`

  Returns a promise delivered with `nil` when the animation completes."
  [opts body-fn]
  (let [{:keys [duration delay]
         :or   {delay 0.0}} opts
        options   (resolve-options opts)
        p         (promise)
        anim-blk  (blk/make-void-block body-fn)
        done-blk  (blk/make-void-block #(deliver p nil))]
    (objc-rt/msg-send :void
                      (grease/get-objc-class "UIView")
                      "animateWithDuration:delay:options:animations:completion:"
                      :float64 (double duration)
                      :float64 (double delay)
                      :int64   (long options)
                      :pointer anim-blk
                      :pointer done-blk)
    p))

;; =============================================================================
;; spring (Phase 5.2)
;; =============================================================================

(defn spring
  "Runs `body-fn` inside a spring UIView animation.

  `opts` map keys:
  - `:duration`  — seconds (required)
  - `:delay`     — seconds before starting (default 0.0)
  - `:damping`   — spring damping ratio 0.0–1.0 (default 0.7)
  - `:velocity`  — initial velocity (default 0.0)

  Returns a promise delivered with `nil` when the animation completes."
  [opts body-fn]
  (let [{:keys [duration delay damping velocity]
         :or   {delay 0.0 damping 0.7 velocity 0.0}} opts
        p        (promise)
        anim-blk (blk/make-void-block body-fn)
        done-blk (blk/make-void-block #(deliver p nil))]
    (objc-rt/msg-send :void
                      (grease/get-objc-class "UIView")
                      "animateWithDuration:delay:usingSpringWithDamping:initialSpringVelocity:options:animations:completion:"
                      :float64 (double duration)
                      :float64 (double delay)
                      :float64 (double damping)
                      :float64 (double velocity)
                      :int64   0
                      :pointer anim-blk
                      :pointer done-blk)
    p))

;; =============================================================================
;; chain (Phase 5.3)
;; =============================================================================

(defn chain
  "Chains animations sequentially; each starts when the previous completes.

  `pairs` is a flat sequence of `[opts body-fn]` pairs.
  Returns a promise delivered with `nil` when the last animation completes."
  [& pairs]
  (let [pair-vec (partition 2 pairs)]
    (if (empty? pair-vec)
      (doto (promise) (deliver nil))
      (let [[opts body-fn] (first pair-vec)
            rest-pairs     (rest pair-vec)
            p              (promise)
            p1             (animate opts body-fn)]
        (future
          (deref p1)
          (if (seq rest-pairs)
            (deliver p (deref (apply chain (mapcat identity rest-pairs))))
            (deliver p nil)))
        p))))

;; =============================================================================
;; anim-> threading macro (Phase 5.4)
;; =============================================================================

(defmacro anim->
  "Returns a zero-arg fn that applies a sequence of animated property setters
  to `view`.

  Each form `(op & args)` is expanded to `(grease.ios.anim/set-<op>! view & args)`.

  Supported ops:
  - `(alpha v)`           → [[set-alpha!]]
  - `(bg color-spec)`     → [[set-bg!]]
  - `(frame x y w h)`    → [[set-frame!]]

  Example:
    (animate {:duration 0.3}
      (anim-> my-view
        (alpha 0.5)
        (bg :red)
        (frame 0 0 320 50)))"
  [view & forms]
  `(fn []
     ~@(mapv (fn [[op & args]]
               `(~(symbol "grease.ios.anim" (str "set-" (name op) "!"))
                 ~view ~@args))
             forms)))
