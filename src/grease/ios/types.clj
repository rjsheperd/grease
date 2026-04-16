(ns grease.ios.types
  "Type coercion bridge between Clojure values and ObjC FFI values.

  Loads [[resources/grease/types.edn]] at startup, resolves coercer symbols
  to functions once (cached), and dispatches on type-name string.

  Supports auto-registration of ObjC class types (any class whose pointer
  should be passed and returned opaquely, without Clojure conversion).

  Usage:
    (types/coerce-in  \"NSString\" \"hello\")  ;; -> NSString pointer
    (types/coerce-out \"NSString\" ptr)         ;; -> \"hello\"
    (types/encoding-for \"NSString\")            ;; -> \"@\"
    (types/register-class! \"AVPlayer\")         ;; -> adds opaque pointer entry"
  (:require [clojure.edn :as edn]
            [grease.ios-host :as host]))

;; =============================================================================
;; BOOL helpers (no native deps — referenced from types.edn)
;; =============================================================================

(defn bool->int
  "Converts a Clojure boolean to 0/1 for ObjC BOOL args."
  [b]
  (if b 1 0))

(defn int->bool
  "Converts an ObjC BOOL (0 or 1) to a Clojure boolean."
  [i]
  (not= 0 i))

;; =============================================================================
;; Type table
;; =============================================================================

(def ^:private type-table
  "Atom holding the runtime type map: type-name -> {:encoding :clj :coerce-in-fn :coerce-out-fn}"
  (atom {}))

(defn- resolve-coercer
  "Resolves a coercer symbol to a function via [[resolve]].
  Namespaces must already be loaded before calling this.
  Returns the function or throws with a helpful message."
  [sym]
  (or (resolve sym)
      (throw (ex-info (str "Cannot resolve coercer: " sym)
                      {:sym sym}))))

(defn- load-entry
  "Loads a single types.edn entry, resolving coercer symbols to functions."
  [[type-name {:keys [coerce-in coerce-out] :as entry}]]
  [type-name (assoc entry
                    :coerce-in-fn  (resolve-coercer coerce-in)
                    :coerce-out-fn (resolve-coercer coerce-out))])

(defn- load-types-edn
  "Reads types.edn from the classpath resource and resolves coercers."
  []
  (let [content (host/read-resource "grease/types.edn")]
    (when-not content
      (throw (ex-info "Cannot find grease/types.edn on classpath" {})))
    (into {} (map load-entry (edn/read-string {:readers {}} content)))))

;; Lazily resolved reference to grease.ios.foundation/null-ptr.
;; Deferred until first use so load order between types and foundation is flexible.
;; NOTE: we deref the Var (with @v) to store the function, not the Var itself.
;; Storing the Var would require two calls: once to deref the Var, once to invoke it.
;; Storing the function (@v) means (@null-ptr-ref) is a single call → ffi.Pointer.
(def ^:private null-ptr-ref
  (delay (or (when-let [v (resolve 'grease.ios.foundation/null-ptr)] @v)
             (throw (ex-info "Cannot resolve grease.ios.foundation/null-ptr — load foundation first" {})))))

(defn init!
  "Loads types.edn and populates the type table.
  Called once at engine startup.  Safe to call multiple times (idempotent)."
  []
  (reset! type-table (load-types-edn)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn register-class!
  "Registers class-name as an opaque pointer type.
  Called by the spec loader for every class in a loaded framework spec.
  Class types pass and return raw ObjC pointers without Clojure conversion."
  [class-name]
  (swap! type-table assoc class-name
         {:encoding      "@"
          :clj           :pointer
          :coerce-in     'clojure.core/identity
          :coerce-out    'clojure.core/identity
          :coerce-in-fn  identity
          :coerce-out-fn identity}))

(defn encoding-for
  "Returns the single-char ObjC type encoding string for type-name,
  or throws if the type is unknown."
  [type-name]
  (or (get-in @type-table [type-name :encoding])
      (throw (ex-info (str "Unknown type: " type-name)
                      {:type-name type-name
                       :known     (keys @type-table)}))))

(defn coerce-in
  "Applies the :coerce-in function for type-name to value.
  For any :pointer type, Clojure nil is replaced with [[grease.ios.foundation/null-ptr]]."
  [type-name value]
  (let [entry (get @type-table type-name)]
    (when-not entry
      (throw (ex-info (str "Unknown type in coerce-in: " type-name)
                      {:type-name type-name})))
    (if (and (= "@" (:encoding entry)) (nil? value))
      ;; nil pointer: call cached null-ptr function (avoids requiring-resolve on hot path)
      (@null-ptr-ref)
      ((:coerce-in-fn entry) value))))

(defn coerce-out
  "Applies the :coerce-out function for type-name to value.
  Returns value unchanged if type-name is unknown (graceful degradation)."
  [type-name value]
  (if-let [entry (get @type-table type-name)]
    ((:coerce-out-fn entry) value)
    value))

(defn known-type?
  "Returns true if type-name is in the type table."
  [type-name]
  (contains? @type-table type-name))

(defn all-types
  "Returns the set of all registered type names."
  []
  (set (keys @type-table)))
