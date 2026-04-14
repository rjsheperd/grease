(ns grease.ios.objc
  "Runtime ObjC class builder for the Clojure nREPL.

  All low-level FFI calls (get-objc-class, allocate-objc-class!, make-imp, …)
  are compiled functions in com.phronemophobic.grease and exposed via
  sci/copy-var.  This namespace provides a higher-level defclass macro that
  assembles them into a usable interface, plus a msg-send helper for invoking
  ObjC methods from SCI.

  NOTE: The objcjure `objc` macro cannot be used from SCI for selectors that
  contain colons (e.g. `setDelegate:`).  SCI's symbol parser rejects them.
  Use `msg-send` instead.

  Example:

    (require '[grease.ios.objc :as objc-rt])
    (require '[com.phronemophobic.grease :as grease])

    (def last-location (atom nil))
    (def held-mgr      (atom nil))
    (def held-delegate (atom nil))

    (objc-rt/defclass LocationDelegate \"NSObject\"
      \"locationManager:didUpdateLocations:\" \"v@:@@\"
      (fn [_self _cmd _mgr locs]
        (reset! last-location locs)))

    (grease/dispatch-main-async
      #(let [cls-mgr (grease/get-objc-class \"CLLocationManager\")
             mgr     (grease/objc-new cls-mgr)
             d       (objc-rt/new-instance LocationDelegate)]
         (reset! held-mgr mgr)
         (reset! held-delegate d)
         (objc-rt/msg-send :void mgr \"setDelegate:\" :pointer d)
         (objc-rt/msg-send :void mgr \"requestWhenInUseAuthorization\")
         (objc-rt/msg-send :void mgr \"startUpdatingLocation\")))

    ;; After granting permission on device:
    @last-location  ;; => non-nil CLLocation pointer"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]))

;; =============================================================================
;; ObjC type encoding reference
;;
;; Common encodings for class_addMethod's type-encoding argument:
;;   \"v@:\"    void method(id self, SEL _cmd)
;;   \"v@:@\"   void method(id self, SEL _cmd, id arg)
;;   \"v@:@@\"  void method(id self, SEL _cmd, id arg1, id arg2)
;;   \"@@:\"    id   method(id self, SEL _cmd)
;;   \"q@:\"    long long method(id self, SEL _cmd)
;;
;; All object pointers (NSObject*, NSArray*, CLLocationManager*, …) encode as @.
;; Primitive extra args add :int32 (:int8/16/32/64) or :float32/:float64 to
;; the extra-arg-types vec passed to make-imp.
;; =============================================================================

(defn new-instance
  "Returns a new instance of cls-or-name.
  Accepts either a class pointer (from defclass) or a class-name string."
  [cls-or-name]
  (let [cls (if (string? cls-or-name)
              (grease/get-objc-class cls-or-name)
              cls-or-name)]
    (grease/objc-new cls)))

(defn msg-send
  "Sends an ObjC message to obj using the selector named by sel-str.

  Use this instead of the `objcjure/objc` macro when calling from the SCI
  nREPL — SCI's symbol parser rejects selectors containing colons, so the
  `objc` macro is unusable for multi-part selectors like `setDelegate:`.

  ret-type   — return type keyword: :void :pointer :int64 :int32 etc.
  obj        — ObjC object pointer (e.g. from objc-new or new-instance)
  sel-str    — selector string e.g. \"setDelegate:\" \"startUpdatingLocation\"
  typed-args — flat alternating pairs of type-keyword and value for each
               extra argument beyond self and _cmd, e.g.:
               :pointer delegate-ptr

  Examples:
    (msg-send :void mgr \"setDelegate:\" :pointer d)
    (msg-send :void mgr \"requestWhenInUseAuthorization\")
    (msg-send :pointer view \"layer\")"
  [ret-type obj sel-str & typed-args]
  (let [sel (grease/register-objc-sel sel-str)]
    (apply ffi/call
           "objc_msgSend" ret-type
           :pointer obj :pointer sel
           typed-args)))

(defn- parse-extra-arg-types
  "Infers extra-arg-types (beyond self + _cmd) from a type-encoding string.
  Only handles the common all-pointer cases.  For mixed primitive/pointer
  methods pass extra-arg-types explicitly to make-imp instead."
  [^String encoding]
  ;; encoding format: rettype self(_cmd) arg1 arg2 …
  ;; drop first 3 chars (ret + @ + :), remaining chars = extra arg types
  (let [extras (subs encoding 3)]
    (mapv (fn [c] (case c
                    \@ :pointer
                    \q :int64
                    \i :int32
                    \l :int64
                    ;; default: treat as opaque pointer
                    :pointer))
          extras)))

(defn- ret-type-from-encoding
  "Extracts the return type keyword from the first char of a type-encoding."
  [^String encoding]
  (case (first encoding)
    \v :void
    \@ :pointer
    \q :int64
    \i :int32
    \l :int64
    :void))

(defmacro defclass
  "Define a new ObjC class at runtime from the Clojure nREPL.

  class-name   — symbol, becomes a def bound to the Class pointer
  superclass   — string, e.g. \"NSObject\"
  method-specs — flat sequence of triples:
                   selector-string  type-encoding  fn-form
                 e.g.:
                   \"locationManager:didUpdateLocations:\" \"v@:@@\"
                   (fn [self _cmd mgr locs] ...)

  The fn receives raw pointer values for all ObjC object args.
  Store the delegate instance in an atom to prevent ARC from releasing it.

  Type encodings:
    v = void   @ = id/object   : = SEL   q = long long   i = int"
  [class-name superclass & method-specs]
  ;; NOTE: Use fully-qualified names throughout the syntax-quote template.
  ;; SCI evaluates macro syntax-quote at expansion time (not definition time),
  ;; so namespace aliases like `grease/` are resolved in the CALLING namespace,
  ;; not the defining namespace.  Fully-qualified names work in any namespace.
  (let [cls-sym (gensym "cls")]
    `(let [~cls-sym (com.phronemophobic.grease/allocate-objc-class!
                     ~(str class-name)
                     (com.phronemophobic.grease/get-objc-class ~superclass))]
       ~@(for [[sel-str enc f] (partition 3 method-specs)]
           (let [extra-sym (gensym "extra-types")
                 ret-sym   (gensym "ret-type")
                 imp-sym   (gensym "imp")
                 sel-sym   (gensym "sel")]
             `(let [~extra-sym (grease.ios.objc/parse-extra-arg-types ~enc)
                    ~ret-sym   (grease.ios.objc/ret-type-from-encoding ~enc)
                    ~imp-sym   (com.phronemophobic.grease/make-imp ~f ~extra-sym ~ret-sym)
                    ~sel-sym   (com.phronemophobic.grease/register-objc-sel ~sel-str)]
                (com.phronemophobic.grease/add-objc-method!
                 ~cls-sym ~sel-sym ~imp-sym ~enc))))
       (com.phronemophobic.grease/register-objc-class! ~cls-sym)
       (def ~class-name ~cls-sym))))
