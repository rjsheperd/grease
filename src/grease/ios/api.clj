(ns grease.ios.api
  "Public user-facing API for the data-driven iOS framework engine.

  Provides ~make~, ~call~, ~get-prop~, ~set-prop!~, ~enum~, ~load!~,
  and ~reload!~.  All framework knowledge comes from EDN specs; no
  per-framework Clojure code is required.

  Usage:
    (api/load!)
    (let [arr (api/make \"NSMutableArray\" \"array\")]
      (api/call arr \"addObject:\" some-ns-obj)
      (api/call arr \"count\"))   ; -> 1

  An [[ObjcObject]] record bundles the ObjC class name with the raw pointer
  so that ~call~, ~get-prop~, and ~set-prop!~ can look up method specs
  without requiring the caller to repeat the class name."
  (:require [grease.ios.invoke :as invoke]
            [grease.ios.naming :as naming]
            [grease.ios.patterns :as patterns]
            [grease.ios.registry :as registry]
            [grease.ios.types :as types]))

;; =============================================================================
;; ObjcObject — typed instance handle
;; =============================================================================

(defrecord ObjcObject
           [class-name
            ^:no-doc ptr]
  Object
  (toString [_] (str "#ObjcObject[" class-name "]")))

(defn objc-object?
  "Returns true if x is an [[ObjcObject]] (typed engine instance handle)."
  [x]
  (instance? ObjcObject x))

(defn wrap
  "Wraps a raw ObjC pointer as an [[ObjcObject]] with the given class-name.
  Use this to bring pointers obtained from existing wrapper functions into
  the engine world."
  [class-name ptr]
  (->ObjcObject class-name ptr))

;; =============================================================================
;; Method resolution
;; =============================================================================

(defn- resolve-method
  "Finds the method map in class-spec for selector-or-clj-name.
  Accepts a string (ObjC selector) or a keyword (Clojure name).
  Returns nil if not found."
  [class-name selector-or-kw]
  (when-let [cs (registry/class-spec class-name)]
    (let [all-methods (concat (:methods cs) (:init cs))]
      (if (keyword? selector-or-kw)
        (some #(when (= (::naming/clj-name %) (name selector-or-kw)) %) all-methods)
        (some #(when (= (:selector %) selector-or-kw) %) all-methods)))))

(defn- require-method
  "Like [[resolve-method]] but throws [[clojure.lang.ExceptionInfo]] on failure."
  [class-name selector-or-kw]
  (or (resolve-method class-name selector-or-kw)
      (throw (ex-info (str "No method found: " class-name " " selector-or-kw)
                      {:class class-name :selector selector-or-kw}))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn load!
  "Initialises the engine: loads naming rules, type bridge, and all specs.
  Must be called before any other api function."
  []
  (naming/init!)
  (types/init!)
  (registry/load-all!))

(defn reload!
  "Reloads all specs from disk.  Naming and type rules are also re-initialised."
  []
  (load!))

;; =============================================================================
;; Instance creation
;; =============================================================================

(defn make
  "Creates an ObjC instance by calling a class-level factory selector.

  class-name  — ObjC class name string e.g. ~\"NSMutableArray\"~
  selector    — factory selector string e.g. ~\"array\"~
  args        — Clojure values for any selector arguments

  Returns an [[ObjcObject]] wrapping the new instance pointer."
  [class-name selector & args]
  (let [method (require-method class-name selector)
        ptr    (invoke/dispatch-class-raw! class-name selector method args)]
    (->ObjcObject class-name ptr)))

;; =============================================================================
;; Instance dispatch
;; =============================================================================

(defn call
  "Sends a message to obj.

  obj              — [[ObjcObject]] or raw ObjC pointer
  selector-or-kw   — selector string e.g. ~\"addObject:\"~ or keyword e.g. ~:add-object~
  args             — Clojure values for any extra arguments

  When obj is an [[ObjcObject]], the class name is used to look up the method
  spec.  When obj is a raw pointer, pass a class-name string as the first arg
  after selector-or-kw using the three-arg overload (see [[call*]])."
  [obj selector-or-kw & args]
  (if (instance? ObjcObject obj)
    (let [method (require-method (:class-name obj) selector-or-kw)]
      (invoke/dispatch! (:ptr obj) (:selector method) method args))
    (throw (ex-info "call requires an ObjcObject; use wrap to promote a raw pointer"
                    {:obj obj}))))

(defn call*
  "Like [[call]] but accepts a raw ObjC pointer with an explicit class-name.

  Useful when working with pointers that predate the engine."
  [ptr class-name selector-or-kw & args]
  (let [method (require-method class-name selector-or-kw)]
    (invoke/dispatch! ptr (:selector method) method args)))

;; =============================================================================
;; Properties
;; =============================================================================

(defn get-prop
  "Reads a property from obj by calling its getter selector."
  [obj prop-kw-or-str]
  (call obj prop-kw-or-str))

(defn set-prop!
  "Writes a property value to obj by calling the synthesized setter.

  The setter name is derived from the property's ~::naming/setter-name~
  annotation.  Falls back to the ObjC convention ~setFoo:~ if not found."
  [obj prop-kw-or-str value]
  (when-let [cs (registry/class-spec (:class-name obj))]
    (let [all-props (:properties cs)
          prop      (some #(when (= (if (keyword? prop-kw-or-str)
                                      (name prop-kw-or-str)
                                      prop-kw-or-str)
                                    (::naming/clj-name %))
                             %)
                          all-props)]
      (if prop
        (let [setter-sel (::naming/setter-name prop)]
          (call* (:ptr obj) (:class-name obj) setter-sel value))
        (throw (ex-info (str "No property found: " (:class-name obj) " " prop-kw-or-str)
                        {:class (:class-name obj) :prop prop-kw-or-str}))))))

;; =============================================================================
;; KVO — key-value observing
;; =============================================================================

(defn watch
  "Registers a KVO callback on obj for the given key-path.

  obj           — [[ObjcObject]] wrapping the target
  key-path-or-kw — property name as keyword e.g. ~:status~ or string ~\"status\"~
  callback       — one-arg fn called when the property changes; receives the
                   opaque context integer (use [[unwatch]] handle to cancel)

  Returns an opaque handle map — pass to [[unwatch]] to deregister.

  Requires [[patterns/init!]] to have been called first (done automatically by
  [[load!]] when running on device)."
  [obj key-path-or-kw callback]
  (let [kp (if (keyword? key-path-or-kw) (name key-path-or-kw) key-path-or-kw)]
    (patterns/kvo-watch! obj kp callback)))

(defn unwatch
  "Removes the KVO observer identified by handle (returned by [[watch]])."
  [handle]
  (patterns/kvo-unwatch! handle))

;; =============================================================================
;; Enums
;; =============================================================================

(defn enum
  "Returns the raw integer value of an enum constant, or nil if not found.

  enum-name — ObjC enum name string e.g. ~\"AVPlayerStatus\"~
  kw        — Clojure keyword e.g. ~:ready-to-play~"
  [enum-name kw]
  (registry/enum-raw-for enum-name kw))
