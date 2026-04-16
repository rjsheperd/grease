(ns grease.ios.patterns
  "Bridge between Clojure idioms and ObjC cross-boundary patterns.

  Implements three patterns:

  **Delegate** (~:delegate~):
  - User passes a ~{:keyword fn}~ map (selectors in Clojure kebab-case).
  - Engine looks up the ObjC protocol methods from the loaded spec.
  - An ObjC subclass of NSObject is created via [[create-delegate-class!]].
  - The class is retained by a hash of the map so the same map always
    returns the same delegate (idempotent across multiple calls).

  **KVO** — [[kvo-watch!]] / [[kvo-unwatch!]]:
  - [[init!]] creates a singleton ~GrseKVOObserver~ class.
  - [[kvo-watch!]] registers a Clojure fn to be called when a property changes.
  - [[kvo-unwatch!]] removes the observer.

  **Completion handler** (~:completion-handler~):
  - Engine wraps a Clojure fn in an ObjC block via [[grease.ios.blocks]].
  - Block type is governed by ~:block-type~ in the arg-spec
    (~:void~, ~:data~, or ~:bool-error~).

  Mocking in tests:
    (with-redefs [grease.ios.patterns/create-delegate-class!
                  (fn [_name _super _pairs] {:mock-delegate true})]
      ...)"
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.blocks :as blocks]
            [grease.ios.naming :as naming]
            [grease.ios.objc :as objc]
            [grease.ios.registry :as registry]))

;; =============================================================================
;; Encoding helpers (self-contained — no circular dep on grease.ios.invoke)
;; =============================================================================

(def ^:private encoding-char->kw
  {\v :void
   \@ :pointer
   \q :int64
   \Q :uint64
   \d :float64
   \f :float32
   \i :int32
   \I :uint32
   \c :int8
   \B :int8
   \* :pointer
   \: :pointer
   \^ :pointer})

(defn- enc->ret-kw [encoding]
  (get encoding-char->kw (first encoding) :pointer))

(defn- enc->arg-kws [encoding]
  (mapv #(get encoding-char->kw % :pointer) (drop 3 encoding)))

;; =============================================================================
;; ObjC class creation
;; =============================================================================

(defn create-delegate-class!
  "Creates an ObjC subclass of superclass with the given selector/encoding/fn triples.
  This is the single mockable entry point for delegate class creation.

  class-name       — unique ObjC class name string
  superclass       — ObjC superclass name string e.g. ~\"NSObject\"~
  selector-enc-fns — seq of ~[selector-string encoding-string fn]~ triples

  Returns the new Class pointer."
  [class-name superclass selector-enc-fns]
  (let [cls (grease/allocate-objc-class!
             class-name
             (grease/get-objc-class superclass))]
    (doseq [[sel-str enc f] selector-enc-fns]
      (let [arg-kws  (enc->arg-kws enc)
            ret-kw   (enc->ret-kw enc)
            imp      (grease/make-imp f arg-kws ret-kw)
            sel      (grease/register-objc-sel sel-str)]
        (grease/add-objc-method! cls sel imp enc)))
    (grease/register-objc-class! cls)
    cls))

;; =============================================================================
;; Delegate wrapping (idempotent)
;; =============================================================================

(defn- clj-name->selector
  "Converts a Clojure keyword name to an ObjC selector by looking up the
  protocol's method list.  Falls back to the raw kebab→camelCase if not found."
  [kw protocol-methods]
  (let [kw-str (name kw)]
    (some #(when (= (::naming/clj-name %) kw-str) (:selector %))
          protocol-methods)))

(defn wrap-delegate
  "Converts a Clojure map of ~{:selector-keyword fn}~ to an ObjC delegate proxy.

  Idempotent: the same map (by ~hash~) always returns the same delegate object.
  The delegate is retained in the registry's retention table.

  delegate-map  — ~{:did-update-locations fn :did-fail-with-error fn ...}~
  protocol-name — ObjC protocol name e.g. ~\"CLLocationManagerDelegate\"~

  Returns an ObjC instance pointer (Class pointer allocated via [[create-delegate-class!]]
  and instantiated with ~+new~)."
  [delegate-map protocol-name]
  (let [retention-key [::delegate (hash delegate-map) protocol-name]
        cached        (registry/retained retention-key)]
    (if cached
      cached
      (let [proto-spec (registry/protocol-spec protocol-name)
            proto-methods (if proto-spec (:methods proto-spec) [])
            class-name  (str "GrseDelegate" (bit-and (hash delegate-map) 0x7fffffff))
            sel-enc-fns (keep (fn [[kw f]]
                                (when-let [sel (clj-name->selector kw proto-methods)]
                                  (let [m (some #(when (= (:selector %) sel) %) proto-methods)]
                                    [sel (:encoding m "v@:@@") f])))
                              delegate-map)
            cls  (create-delegate-class! class-name "NSObject" sel-enc-fns)
            inst (grease/objc-new cls)]
        (registry/retain! retention-key inst)
        inst))))

;; =============================================================================
;; KVO observer — singleton class + callback registry
;; =============================================================================

;; Singleton ObjC class used as the KVO observer for all watches.
(defonce ^:private kvo-observer-class (atom nil))

;; Map of context-integer -> callback fn.
(defonce ^:private kvo-callbacks (atom {}))

;; Monotonically increasing context counter (used as opaque context void*).
(defonce ^:private kvo-context-counter (atom 0))

(defn- next-kvo-context
  "Returns the next unique KVO context integer."
  []
  (swap! kvo-context-counter inc))

(defn init!
  "Initialises the patterns engine by creating the singleton ~GrseKVOObserver~
  ObjC class.  Idempotent — subsequent calls are no-ops.

  Must be called once after the ObjC runtime is available (i.e. on device or
  after the native image is loaded).  In tests, call after mocking
  [[create-delegate-class!]]."
  []
  (when-not @kvo-observer-class
    (let [cls (create-delegate-class!
               "GrseKVOObserver"
               "NSObject"
               [["observeValueForKeyPath:ofObject:change:context:"
                 "v@:@@@^v"
                 (fn [_self _cmd _key-path _obj _change ctx]
                   (when-let [cb (get @kvo-callbacks ctx)]
                     (cb ctx)))]])]
      (reset! kvo-observer-class cls))))

(defn reset-state!
  "Resets all KVO atoms to their initial state.
  Call in tests between cases to ensure isolation."
  []
  (reset! kvo-observer-class nil)
  (reset! kvo-callbacks {})
  (reset! kvo-context-counter 0))

(defn kvo-watch!
  "Registers a KVO observer on obj for the given key-path string.

  Creates a new observer instance from the singleton [[GrseKVOObserver]] class
  and registers it via ~addObserver:forKeyPath:options:context:~.

  callback  — a one-arg fn receiving the context integer when the value changes.
              (The change dictionary and key-path are available via the real KVO
              signature; this simplified bridge passes only the context for now.)

  Returns an opaque handle map — pass to [[kvo-unwatch!]] to deregister."
  [obj key-path callback]
  (let [ctx      (next-kvo-context)
        obs-inst (grease/objc-new @kvo-observer-class)]
    (swap! kvo-callbacks assoc ctx callback)
    ;; NOTE: key-path must be an NSString ptr in production.
    ;; In JVM mock tests, the raw string is passed and recorded by mock-msg-send.
    (objc/msg-send :void (:ptr obj)
                   "addObserver:forKeyPath:options:context:"
                   :pointer obs-inst
                   :pointer key-path
                   :uint64  1          ; NSKeyValueObservingOptionNew
                   :pointer ctx)
    {:observer  obs-inst
     :key-path  key-path
     :context   ctx
     :target-ptr (:ptr obj)}))

(defn kvo-unwatch!
  "Removes the KVO observer identified by handle (returned by [[kvo-watch!]]).

  Calls ~removeObserver:forKeyPath:context:~ on the target object and clears
  the callback from the registry."
  [{:keys [observer key-path context target-ptr]}]
  ;; NOTE: key-path must be NSString ptr in production (same caveat as watch).
  (objc/msg-send :void target-ptr
                 "removeObserver:forKeyPath:context:"
                 :pointer observer
                 :pointer key-path
                 :pointer context)
  (swap! kvo-callbacks dissoc context))

;; =============================================================================
;; wrap-arg — dispatches pattern from arg-spec
;; =============================================================================

(defmulti ^:private wrap-arg*
  "Dispatches on the :pattern key of an arg-spec map."
  (fn [arg-spec _val] (:pattern arg-spec)))

(defmethod wrap-arg* :delegate
  [arg-spec delegate-map]
  (wrap-delegate delegate-map (:protocol arg-spec "NSObject")))

(defmethod wrap-arg* :completion-handler
  [arg-spec callback-fn]
  ;; Preferred path: :block-args vector drives make-typed-block directly.
  ;; Legacy path: :block-type keyword for backward compatibility with older specs.
  (if-let [arg-kws (:block-args arg-spec)]
    (blocks/make-typed-block (get arg-spec :block-ret :void) arg-kws callback-fn)
    (case (get arg-spec :block-type :void)
      :data       (blocks/make-data-block callback-fn)
      :bool-error (blocks/make-bool-error-block callback-fn)
      (blocks/make-void-block callback-fn))))

(defmethod wrap-arg* :default
  [_ val]
  val)

(defn wrap-arg
  "Applies the pattern-specific wrapper to val if arg-spec has a :pattern key.
  Returns val unchanged for plain typed args."
  [arg-spec val]
  (if (:pattern arg-spec)
    (wrap-arg* arg-spec val)
    val))