(ns grease.ios.kvo
  "Key-Value Observing (KVO) as Clojure watchers.

  [[observe]] wraps an ObjC keypath observation in a ref-like value that
  supports `deref`, `add-watch`, and `remove-watch`.  [[release!]] tears
  down the underlying ObjC observation.  [[reaction]] builds a derived
  ref that re-evaluates whenever any of its source observables change.

  Internally, a single `GreaseKVOObserver` ObjC class is registered once.
  For each [[observe]] call a fresh instance is created; callbacks dispatch
  to a per-instance Clojure atom via the instance pointer address."
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Internal dispatch table
;; =============================================================================

(def ^:no-doc observer-dispatch
  "Map of {instance-ptr-address → atom} for all live KVO observers."
  (atom {}))

;; NSKeyValueObservingOptionNew (1) | NSKeyValueObservingOptionOld (2)
(def ^:private kvo-options 3)

;; =============================================================================
;; GreaseKVOObserver ObjC class (registered once at ns load)
;; =============================================================================

;; NOTE: defonce inside the ns body runs once per classloader load.
;; The IMP dispatches on `self` address so one class serves all observations.
(defonce ^:no-doc _kvo-observer-cls
  (let [cls (grease/allocate-objc-class!
             "GreaseKVOObserver"
             (grease/get-objc-class "NSObject"))
        imp (grease/make-imp
             (fn [self _cmd _keypath _object change _context]
               (when-let [a (get @observer-dispatch
                                 (.address ^Object self))]
                 ;; Extract new value from change dict using NSKeyValueChangeNewKey (@"new")
                 (let [new-key (f/->nsstring "new")
                       new-val (objc-rt/msg-send :pointer change
                                                 "objectForKey:"
                                                 :pointer new-key)]
                   (reset! a new-val))))
             [:pointer :pointer :pointer :pointer :pointer]
             :void)
        sel (grease/register-objc-sel
             "observeValueForKeyPath:ofObject:change:context:")]
    (grease/add-objc-method! cls sel imp "v@:@@@@")
    (grease/register-objc-class! cls)
    cls))

;; =============================================================================
;; KVORef type
;; =============================================================================

(deftype KVORef [obj keypath observer-ptr retain-key internal]
  clojure.lang.IDeref
  (deref [_] @internal))

;; IRef is not available in SCI — provide watcher delegation as plain fns.
(defn add-kvo-watch
  "Adds a watch function `f` under key `k` to a [[KVORef]]'s internal atom."
  [^KVORef obs-ref k f]
  (add-watch (.-internal obs-ref) k f)
  obs-ref)

(defn remove-kvo-watch
  "Removes the watch registered under key `k` from a [[KVORef]]."
  [^KVORef obs-ref k]
  (remove-watch (.-internal obs-ref) k)
  obs-ref)

;; =============================================================================
;; Public API (Phase 2.2 / 2.3)
;; =============================================================================

(defn observe
  "Creates a KVO observation of `keypath` on ObjC `obj`.

  Returns a [[KVORef]] implementing `IDeref` and `IRef`:
  - `deref` — current observed value (raw ObjC pointer)
  - `add-watch` / `remove-watch` — standard Clojure watcher API

  The initial value is obtained from `valueForKeyPath:` at creation time.
  Subsequent values come from KVO `NSKeyValueChangeNewKey` entries.

  Call [[release!]] when done to stop observing and free resources."
  [obj keypath]
  (let [ks       (f/->nsstring keypath)
        initial  (objc-rt/msg-send :pointer obj "valueForKeyPath:" :pointer ks)
        a        (atom initial)
        obs      (grease/objc-new _kvo-observer-cls)
        rk       (retain/retain! (gensym "kvo-") obs ::kvo-observer)]
    (swap! observer-dispatch assoc
           (.address ^Object obs) a)
    (objc-rt/msg-send :void obj
                      "addObserver:forKeyPath:options:context:"
                      :pointer obs
                      :pointer ks
                      :int64 kvo-options
                      :pointer (f/null-ptr))
    (KVORef. obj keypath obs rk a)))

(defn release!
  "Stops the KVO observation held by `obs-ref` and frees resources.

  Fires all watches with `nil` as the new value (signals observation ended),
  then removes them."
  [^KVORef obs-ref]
  (let [obj          (.-obj obs-ref)
        keypath      (.-keypath obs-ref)
        observer-ptr (.-observer-ptr obs-ref)
        rk           (.-retain-key obs-ref)]
    ;; Stop observing
    (objc-rt/msg-send :void obj
                      "removeObserver:forKeyPath:"
                      :pointer observer-ptr
                      :pointer (f/->nsstring keypath))
    ;; Clean up
    (swap! observer-dispatch dissoc
           (.address ^Object observer-ptr))
    (retain/release! rk)))

;; =============================================================================
;; reaction (Phase 2.4)
;; =============================================================================

(defn reaction
  "Returns a derived atom that re-evaluates `expr-fn` whenever any
  observable in `observables` changes.

  `expr-fn` is called with no arguments; it should `deref` the observables
  it depends on.  The derived atom is only updated (and watches fired) when
  the result of `expr-fn` differs from the previous value.

  The derived atom is a plain Clojure atom; it is not backed by an ObjC
  observation and does not need [[release!]]."
  [expr-fn & observables]
  (let [derived (atom (expr-fn))
        update! (fn [_ _ _ _]
                  (let [new-val (expr-fn)]
                    (when (not= new-val @derived)
                      (reset! derived new-val))))]
    (doseq [obs observables]
      (add-watch obs (gensym "reaction-") update!))
    derived))
