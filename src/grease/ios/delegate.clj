(ns grease.ios.delegate
  "ObjC delegate classes as Clojure agents.

  [[defdelegate]] defines a named ObjC class and a factory function.
  Calling the factory returns a Clojure agent whose state is updated by
  the delegate's ObjC callbacks.

  Each ObjC method IMP dispatches to a per-instance Clojure agent via an
  internal dispatch table keyed on the `self` pointer address.  Errors
  in transition functions are caught and stored under `::last-error` in
  state rather than crashing the callback thread.

  Example:
    (defdelegate LocationDelegate \"CLLocationManagerDelegate\"
      {:state   {:location nil :error nil}
       :methods {[:location-manager _ :did-update-locations locs]
                 (fn [state _mgr locs]
                   (assoc state :location locs))}})"
  (:require [clojure.string :as str]
            [com.phronemophobic.grease :as grease]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Selector reconstruction (Phase 1.2) — pure, testable on JVM
;; =============================================================================

(defn- kw->camel
  "Converts :did-update-locations to \"didUpdateLocations\"."
  [kw]
  (let [parts (str/split (name kw) #"-")]
    (apply str (first parts) (map str/capitalize (rest parts)))))

(defn method-descriptor->selector
  "Converts a method descriptor vector to an ObjC selector string.

  Elements are alternating `[part binding]` pairs.  Each `part` keyword
  contributes `camelCase:` to the selector; non-keyword parts are ignored.

  Examples:
    [:did-finish-loading _] => \"didFinishLoading:\"
    [:location-manager _ :did-update-locations locs] => \"locationManager:didUpdateLocations:\""
  [descriptor]
  (->> (partition 2 descriptor)
       (keep (fn [[part _]]
               (when (keyword? part)
                 (str (kw->camel part) ":"))))
       (apply str)))

;; =============================================================================
;; Instance dispatch table
;; =============================================================================

(def ^:no-doc instances
  "Map of {self-ptr-address → agent} for all live delegate instances."
  (atom {}))

(defn ^:no-doc register-instance!
  "Associates ObjC `ptr` with Clojure `ag` in the dispatch table."
  [ptr ag]
  (swap! instances assoc (.address ^Object ptr) ag))

(defn ^:no-doc unregister-instance!
  "Removes the entry for ObjC `ptr` from the dispatch table."
  [ptr]
  (swap! instances dissoc (.address ^Object ptr)))

(defn ^:no-doc lookup-agent
  "Returns the agent for ObjC `self`, or nil if not found."
  [self]
  (get @instances (.address ^Object self)))

;; =============================================================================
;; Public helpers (Phase 1.5)
;; =============================================================================

(defn objc-ptr
  "Returns the ObjC pointer stored in agent `ag` metadata under `::objc-ptr`."
  [ag]
  (::objc-ptr (meta ag)))

(defn release-delegate!
  "Removes `ag` from the dispatch table and the retain registry.
  Call when the delegate is no longer needed."
  [ag]
  (when-let [ptr (objc-ptr ag)]
    (unregister-instance! ptr)
    (retain/release! (::retain-key (meta ag)))))

;; =============================================================================
;; defdelegate macro (Phases 1.1 / 1.3 / 1.4 / 1.6 / 1.7)
;; =============================================================================

(defmacro defdelegate
  "Define a new ObjC delegate class and a factory function.

  `class-name`  — symbol; the factory `defn` is bound to this name.
  `protocol`    — ObjC protocol name string, e.g. \"CLLocationManagerDelegate\".
  `opts-map`    — literal map with keys:
    `:state`    — initial state for each agent (any Clojure value).
    `:methods`  — map from method-descriptor-vec to state-transition-fn.

  **Method descriptors** are vectors of alternating `[keyword binding]` pairs.
  Each keyword contributes a camelCase selector segment with a trailing colon.
  Bindings are the local names for ObjC pointer args in the IMP function.
  All args are assumed to be pointer-typed (covers the vast majority of
  delegate callbacks; use [[grease.ios.objc/defclass]] for mixed-type methods).

  **Transition fn signature:** `(fn [current-state & objc-args] -> new-state)`.
  Errors are caught; the previous state is preserved and the exception is
  stored under `::last-error`.

  Calling the factory function with no args creates a new ObjC instance and
  returns a Clojure agent.  Agent metadata holds:
  - `::objc-ptr`   — raw ObjC instance pointer
  - `::protocol`   — protocol name string
  - `::retain-key` — retain-registry key for cleanup via [[release-delegate!]]

  The ObjC class is created once (via `defonce`) to prevent
  double-registration crashes on REPL reload.

  Example:
    (defdelegate LocationDelegate \"CLLocationManagerDelegate\"
      {:state   {:location nil :error nil}
       :methods {[:location-manager _ :did-update-locations locs]
                 (fn [state _mgr locs]
                   (assoc state :location locs))
                 [:location-manager _ :did-fail-with-error err]
                 (fn [state _mgr err]
                   (assoc state :error err))}})"
  [class-name protocol opts-map]
  (let [{:keys [state methods]} opts-map
        cls-name-str (str class-name)
        cls-var-sym  (symbol (str "__" class-name "__cls"))
        ;; Compute [selector encoding imp-form] triples at macro-expand time.
        ;; All args assumed to be ObjC pointers; return type always void.
        method-triples
        (mapv (fn [[descriptor transition-fn]]
                (let [sel      (method-descriptor->selector descriptor)
                      arg-syms (mapv second (partition 2 descriptor))
                      n-extra  (count arg-syms)
                      encoding (str "v@:" (apply str (repeat n-extra "@")))]
                  [sel encoding
                   `(fn [self# _cmd# ~@arg-syms]
                      (when-let [ag# (grease.ios.delegate/lookup-agent self#)]
                        (send ag# (fn [st#]
                                    (try
                                      (~transition-fn st# ~@arg-syms)
                                      (catch Exception e#
                                        (assoc st# ::last-error e#)))))))]))
              methods)
        ;; Pre-computed method install forms (avoids nested backtick / gensym issue)
        install-forms
        (mapv (fn [[sel enc imp-form]]
                `(let [extra# ~(vec (repeat (- (count enc) 3) :pointer))
                       imp#   (com.phronemophobic.grease/make-imp ~imp-form extra# :void)
                       sel#   (com.phronemophobic.grease/register-objc-sel ~sel)]
                   (com.phronemophobic.grease/add-objc-method! cls# sel# imp# ~enc)))
              method-triples)]
    `(do
       (defonce ~cls-var-sym
         (let [cls# (com.phronemophobic.grease/allocate-objc-class!
                     ~cls-name-str
                     (com.phronemophobic.grease/get-objc-class "NSObject"))]
           ~@install-forms
           (grease.ios.objc/add-protocol! cls# ~protocol)
           (com.phronemophobic.grease/register-objc-class! cls#)
           cls#))
       (defn ~class-name
         ~(str "Factory: creates a " cls-name-str
               " ObjC instance and returns a Clojure agent.\n"
               "  Use [[grease.ios.delegate/objc-ptr]] to extract the raw pointer\n"
               "  and [[grease.ios.delegate/release-delegate!]] for cleanup.")
         []
         (let [ptr# (grease.ios.objc/new-instance ~cls-var-sym)
               ag#  (agent ~state)
               rk#  (retain/retain! (gensym "delegate-") ptr# ::delegate)]
           (grease.ios.delegate/register-instance! ptr# ag#)
           (vary-meta ag# assoc
                      ::objc-ptr ptr#
                      ::protocol ~protocol
                      ::retain-key rk#))))))
