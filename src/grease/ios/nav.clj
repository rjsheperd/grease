(ns grease.ios.nav
  "UINavigationController-backed navigation for grease screens.

  [[make-nav!]] creates a UINavigationController with a root defscreen spec.
  [[push!]] / [[pop!]] manage the back-stack imperatively.
  [[present-modal!]] / [[dismiss-modal!]] handle modal presentation.
  [[set-title!]] sets the navigation bar title on a VC pointer.
  [[view-controllers]] returns the current VC stack as a Clojure vec.

  Each VC is a `GrseNavVC` instance — a UIViewController subclass that
  mounts a grease hiccup screen into its root view in `viewDidLoad` and
  tears it down in `viewWillDisappear:` when the VC is popped from the stack.

  **Modal teardown note:** `viewWillDisappear:` uses
  `isMovingFromParentViewController` to detect navigation-stack pops.  This
  flag is `false` for modal dismissals.  To tear down a modal screen after
  dismissal, call [[cleanup-vc!]] manually with the VC pointer address, or
  pass a `:on-unmount` hook to the defscreen spec.

  Example:
    (def nav-ctrl (on-main (nav/make-nav! HomeScreen)))
    ;; later:
    (nav/push! nav-ctrl DetailScreen)
    (nav/pop!  nav-ctrl)"
  (:refer-clojure :exclude [pop!])
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; VC registry
;; =============================================================================

(def ^:no-doc nav-vc-registry
  "Map of {vc-address → {:screen-spec spec :nav-key sym :state atom :retain-key rk}}"
  (atom {}))

;; =============================================================================
;; Private cleanup helper (JVM-testable — no ObjC calls)
;; =============================================================================

(defn- cleanup-vc!
  "Tears down the hiccup tree for `vc-addr`, fires `:on-unmount`, and
  removes the entry from [[nav-vc-registry]].

  No-op when `vc-addr` is not registered."
  [vc-addr]
  (when-let [{:keys [screen-spec nav-key state retain-key]}
             (get @nav-vc-registry vc-addr)]
    (hiccup/unmount! nav-key)
    (when-let [on-unmount (:on-unmount screen-spec)]
      (on-unmount {:state state}))
    (retain/release! retain-key)
    (swap! nav-vc-registry dissoc vc-addr)))

;; =============================================================================
;; GrseNavVC ObjC class (registered once at ns load)
;; =============================================================================

;; NOTE: The viewDidLoad override does NOT call super.  UIViewController's
;; default viewDidLoad is a no-op for custom VCs; skipping the super call
;; avoids the need to construct an objc_super struct.

(defonce ^:no-doc _grse-nav-vc-cls
  (let [cls (grease/allocate-objc-class!
             "GrseNavVC"
             (grease/get-objc-class "UIViewController"))

        ;; viewDidLoad — "v@:" — no extra args
        vdl-imp
        (grease/make-imp
         (fn [self _cmd]
           (let [vc-addr (.address ^Object self)]
             (when-let [{:keys [screen-spec nav-key state]}
                        (get @nav-vc-registry vc-addr)]
               (let [root-view (objc-rt/msg-send :pointer self "view")
                     view-fn   (:view screen-spec)]
                 (grease/dispatch-main-async
                  #(do
                     (hiccup/mount! nav-key root-view [state]
                                    (fn [] (view-fn @state)))
                     (when-let [on-mount (:on-mount screen-spec)]
                       (on-mount {:state state :root-view root-view}))))))))
         []
         :void)
        vdl-sel (grease/register-objc-sel "viewDidLoad")

        ;; viewWillDisappear:animated: — "v@:c" — one :int8 extra arg (BOOL)
        ;; isMovingFromParentViewController distinguishes pop from tab-switch
        ;; or modal-cover. For modal dismissals, callers must call cleanup-vc!
        ;; manually (see namespace docstring).
        vwd-imp
        (grease/make-imp
         (fn [self _cmd _animated]
           (let [moving? (not= 0 (long (objc-rt/msg-send
                                        :int64 self
                                        "isMovingFromParentViewController")))]
             (when moving?
               (cleanup-vc! (.address ^Object self)))))
         [:int8]
         :void)
        vwd-sel (grease/register-objc-sel "viewWillDisappear:")]

    (grease/add-objc-method! cls vdl-sel vdl-imp "v@:")
    (grease/add-objc-method! cls vwd-sel vwd-imp "v@:c")
    (grease/register-objc-class! cls)
    cls))

;; =============================================================================
;; Private factory
;; =============================================================================

(defn- make-vc!
  "Allocates a GrseNavVC, creates a state atom, generates a unique nav-key,
  retains the VC pointer, and registers the entry in [[nav-vc-registry]].

  The hiccup tree is NOT mounted here — mounting happens in viewDidLoad when
  UIKit first accesses the VC's view.

  Returns the VC pointer."
  [screen-spec]
  (let [vc      (grease/objc-new _grse-nav-vc-cls)
        state   (atom (:initial-state screen-spec))
        nav-key (gensym "nav-vc-")
        rk      (retain/retain! nav-key vc ::nav-vc)]
    (swap! nav-vc-registry assoc
           (.address ^Object vc)
           {:screen-spec screen-spec
            :nav-key     nav-key
            :state       state
            :retain-key  rk})
    vc))

;; =============================================================================
;; Public API
;; =============================================================================

(defn make-nav!
  "Creates a UINavigationController with `root-screen-spec` as the root page.

  Allocates a GrseNavVC for the root screen and initialises a
  UINavigationController around it.

  Returns the UINavigationController pointer.  Hold it in an atom to prevent
  ARC from collecting it."
  [root-screen-spec]
  (let [root-vc (make-vc! root-screen-spec)]
    (objc-rt/msg-send :pointer
                      (objc-rt/msg-send :pointer
                                        (grease/get-objc-class "UINavigationController")
                                        "alloc")
                      "initWithRootViewController:"
                      :pointer root-vc)))

(defn push!
  "Pushes a new VC for `screen-spec` onto `nav-vc`'s navigation stack.

  Creates a fresh GrseNavVC (the hiccup tree mounts in viewDidLoad) and
  dispatches `pushViewController:animated:` to the main thread.

  `animated` defaults to `true`; pass `false` for an instant transition.

  Returns the new VC pointer (useful for calling [[set-title!]] or
  [[cleanup-vc!]] later)."
  ([nav-vc screen-spec] (push! nav-vc screen-spec true))
  ([nav-vc screen-spec animated]
   (let [vc (make-vc! screen-spec)]
     (grease/dispatch-main-async
      #(objc-rt/msg-send :void nav-vc
                         "pushViewController:animated:"
                         :pointer vc
                         :int64   (if animated 1 0)))
     vc)))

(defn pop!
  "Pops the top VC from `nav-vc`'s navigation stack.

  Dispatches `popViewControllerAnimated:` to the main thread.  The hiccup
  tree is torn down automatically in the VC's `viewWillDisappear:` IMP.

  `animated` defaults to `true`.

  Returns `nav-vc`."
  ([nav-vc] (pop! nav-vc true))
  ([nav-vc animated]
   (grease/dispatch-main-async
    #(objc-rt/msg-send :pointer nav-vc
                       "popViewControllerAnimated:"
                       :int64 (if animated 1 0)))
   nav-vc))

(defn present-modal!
  "Presents `screen-spec` modally from `from-vc`, wrapped in a
  UINavigationController so the modal has a navigation bar.

  Dispatches `presentViewController:animated:completion:` to the main thread.

  See namespace docstring for modal teardown — `viewWillDisappear:` does not
  fire `isMovingFromParentViewController` for dismissals.  Call
  `(cleanup-vc! (.address ^Object modal-vc))` after dismissal, or use a
  `:on-unmount` hook.

  Returns the presented UINavigationController pointer."
  ([from-vc screen-spec] (present-modal! from-vc screen-spec true))
  ([from-vc screen-spec animated]
   (let [vc       (make-vc! screen-spec)
         modal-nc (objc-rt/msg-send :pointer
                                    (objc-rt/msg-send :pointer
                                                      (grease/get-objc-class
                                                       "UINavigationController")
                                                      "alloc")
                                    "initWithRootViewController:"
                                    :pointer vc)]
     (grease/dispatch-main-async
      #(objc-rt/msg-send :void from-vc
                         "presentViewController:animated:completion:"
                         :pointer modal-nc
                         :int64   (if animated 1 0)
                         :pointer (f/null-ptr)))
     modal-nc)))

(defn dismiss-modal!
  "Dismisses the modally presented VC from `vc`.

  Dispatches `dismissViewControllerAnimated:completion:` to the main thread.
  Does NOT automatically tear down the dismissed VC's hiccup tree — see
  [[present-modal!]] docstring.

  `animated` defaults to `true`."
  ([vc] (dismiss-modal! vc true))
  ([vc animated]
   (grease/dispatch-main-async
    #(objc-rt/msg-send :void vc
                       "dismissViewControllerAnimated:completion:"
                       :int64   (if animated 1 0)
                       :pointer (f/null-ptr)))))

(defn set-title!
  "Sets the navigation bar title of `vc` to `title-str`.

  Dispatches to the main thread; safe to call from any thread."
  [vc title-str]
  (grease/dispatch-main-async
   #(objc-rt/msg-send :void vc
                      "setTitle:"
                      :pointer (f/->nsstring title-str))))

(defn view-controllers
  "Returns a Clojure vec of UIViewController pointers currently on
  `nav-vc`'s navigation stack, root first.

  Must be called on the main thread."
  [nav-vc]
  (f/nsarray->vec
   (objc-rt/msg-send :pointer nav-vc "viewControllers")))
