(ns grease.ios.coerce
  "Clojure ↔ ObjC value bridge (auto-coercion layer).

  Converts Clojure values to ObjC NS objects by dispatching on runtime Clojure
  type, and reverses the conversion given an ObjC class name.

  This namespace sits between user code and [[grease.ios.types]], providing
  convenience coercion without requiring callers to know the ObjC type name.

  Usage:
    (coerce/->ns-object \"hello\")   ;; -> NSString pointer
    (coerce/->ns-object [1 2 3])    ;; -> NSArray pointer  (elements auto-boxed)
    (coerce/->ns-object {:a 1})     ;; -> NSDictionary pointer (keys as strings)
    (coerce/->ns-object 42)         ;; -> NSNumber (long) pointer
    (coerce/->ns-object 3.14)       ;; -> NSNumber (double) pointer
    (coerce/->ns-object nil)        ;; -> null-ptr sentinel

    (coerce/from-ns-object \"NSString\" ptr) ;; -> \"hello\"
    (coerce/from-ns-object \"NSNumber\" ptr) ;; -> 42 (long)"
  (:require [grease.ios.foundation :as foundation]
            [grease.ios.types :as types]))

;; =============================================================================
;; Clojure → ObjC
;; =============================================================================

(declare ->ns-object)

(defn- ->ns-object-map
  "Converts a Clojure map to an NSDictionary pointer.
  Keys are coerced via [[->ns-object]] then via [[foundation/->nsstring]] if
  they are keywords or symbols; values are coerced via [[->ns-object]]."
  [m]
  (let [coerce-key (fn [k]
                     (cond
                       (keyword? k) (foundation/->nsstring (name k))
                       (symbol?  k) (foundation/->nsstring (name k))
                       (string?  k) (foundation/->nsstring k)
                       :else        (->ns-object k)))]
    (foundation/->nsdict (into {} (map (fn [[k v]] [(coerce-key k) (->ns-object v)]) m)))))

(defn ->ns-object
  "Converts a Clojure value to the appropriate ObjC NS object pointer.

  Dispatch table:
  - nil                → null-ptr (via [[grease.ios.types/coerce-in]])
  - String             → NSString (via [[grease.ios.foundation/->nsstring]])
  - Long / Integer     → NSNumber (via [[grease.ios.foundation/->nsnumber-long]])
  - Double / Float     → NSNumber (via [[grease.ios.foundation/->nsnumber-double]])
  - IPersistentMap     → NSDictionary (keys coerced to NSString)
  - Sequential (vector, list, seq) → NSArray (elements recursively coerced)
  - other              → passthrough (assumes already a raw ObjC pointer)"
  [v]
  (cond
    (nil? v)       (types/coerce-in "id" nil)
    (string? v)    (foundation/->nsstring v)
    (integer? v)   (foundation/->nsnumber-long v)
    (float? v)     (foundation/->nsnumber-double (double v))
    (ratio? v)     (foundation/->nsnumber-double (double v))
    (decimal? v)   (foundation/->nsnumber-double (double v))
    (number? v)    (foundation/->nsnumber-double v)     ; catches Double
    (map? v)       (->ns-object-map v)
    (sequential? v) (foundation/->nsarray (mapv ->ns-object v))
    :else          v))

;; =============================================================================
;; ObjC → Clojure
;; =============================================================================

(defn from-ns-object
  "Converts an ObjC pointer to the corresponding Clojure value.

  Delegates to [[grease.ios.types/coerce-out]] using class-name to select the
  coercer.  Returns ptr unchanged for unrecognized class names (graceful
  passthrough).

  class-name — ObjC class name string e.g. ~\"NSString\"~, ~\"NSNumber\"~
  ptr        — raw ObjC pointer returned from a msg-send or engine call"
  [class-name ptr]
  (types/coerce-out class-name ptr))
