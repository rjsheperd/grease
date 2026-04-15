(ns grease.ios.repl
  "REPL developer-experience helpers for live iOS exploration.

  Provides ObjC runtime introspection, safer main-thread dispatch, and
  a hot-reload-safe `defclass!` macro for iterative delegate development.

  These helpers are loaded into the SCI context at startup and are available
  from the nREPL without any rebuild.

  Quick reference:

    ;; What class is this pointer?
    (class-name-of some-ptr)  ;; => \"CLLocation\"

    ;; Does this object understand a message?
    (respond-to? mgr \"startUpdatingLocation\")  ;; => true

    ;; What methods does NSString have?
    (take 5 (sort (methods-of \"NSString\")))

    ;; Walk the inheritance chain
    (super-class-of \"UIViewController\")  ;; => \"UIResponder\"

    ;; Run on main thread, get result or throw on error
    (on-main (msg-send :pointer root \"view\"))

    ;; Define a new delegate version without crashing on re-eval
    (defclass! MyDelegate \"NSObject\"
      \"someMethod\" \"v@:\" (fn [_ _] (println \"called!\")))"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; Runtime introspection
;; =============================================================================

(defn class-name-of
  "Returns the ObjC class name of obj as a Clojure string."
  [obj]
  (f/nsstring->str
   (objc-rt/msg-send :pointer
                     (objc-rt/msg-send :pointer obj "class")
                     "description")))

(defn respond-to?
  "Returns true if obj responds to the selector named by sel-str."
  [obj sel-str]
  (let [sel (grease/register-objc-sel sel-str)]
    (not= 0 (objc-rt/msg-send :int8 obj "respondsToSelector:" :pointer sel))))

(defn methods-of
  "Returns a sorted vec of selector-name strings for all methods directly
  defined on the ObjC class named class-name (inherited methods excluded).
  Calls the grease_class_method_names C shim in Bridge.m."
  [class-name]
  (let [cls (grease/get-objc-class class-name)
        arr (ffi/call "grease_class_method_names" :pointer :pointer cls)]
    (sort (mapv f/nsstring->str (f/nsarray->vec arr)))))

(defn super-class-of
  "Returns the superclass name of the ObjC class named class-name, or nil
  if it has no superclass (i.e. is a root class)."
  [class-name]
  (let [cls   (grease/get-objc-class class-name)
        super (objc-rt/msg-send :pointer cls "superclass")]
    (when super
      (f/nsstring->str (objc-rt/msg-send :pointer super "description")))))

(defn inheritance-chain
  "Returns a vec of class-name strings from class-name up to the root."
  [class-name]
  (loop [name class-name acc [class-name]]
    (if-let [s (super-class-of name)]
      (recur s (conj acc s))
      acc)))

;; =============================================================================
;; Safer main-thread dispatch
;; =============================================================================

(defmacro on-main
  "Evaluates body on the main GCD queue and returns the result.
  If body throws, the exception is re-raised on the calling thread.
  Times out after timeout-ms (default 10000) and throws :timeout."
  [& body]
  `(let [result# (promise)]
     (com.phronemophobic.grease/dispatch-main-async
      (fn []
        (try
          (deliver result# {:ok (do ~@body)})
          (catch Exception e#
            (deliver result# {:err {:message (.getMessage e#)
                                    :class   (str (class e#))}})))))
     (let [r# (deref result# 10000 :timeout)]
       (cond
         (= r# :timeout) (throw (ex-info "on-main timed out" {}))
         (:err r#)        (throw (ex-info "Main thread error" (:err r#)))
         :else            (:ok r#)))))

;; =============================================================================
;; Hot-reload-safe defclass
;; =============================================================================

;; ObjC class registration is permanent within a process — re-registering a
;; class with the same name crashes. This counter appends a unique suffix so
;; each REPL re-evaluation produces a new class name.
(def ^:private delegate-version (atom 0))

(defmacro defclass!
  "Like grease.ios.objc/defclass but safe to re-evaluate from the nREPL.
  Appends an auto-incrementing _vN suffix to class-name at macro-expansion
  time and binds the original symbol to the new class pointer.

  Example:
    (defclass! MyDelegate \"NSObject\"
      \"locationManager:didUpdateLocations:\" \"v@:@@\"
      (fn [_ _ _ locs] (reset! last-location locs)))
    ;; Creates MyDelegate_v1 in ObjC runtime, bound to MyDelegate in Clojure.
    ;; Re-evaluating creates MyDelegate_v2, etc."
  [class-name superclass & method-specs]
  (let [n        (swap! delegate-version inc)
        versioned (symbol (str (name class-name) "_v" n))]
    `(do
       (grease.ios.objc/defclass ~versioned ~superclass ~@method-specs)
       (def ~class-name ~versioned))))
