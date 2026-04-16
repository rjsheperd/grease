(ns grease.ios.invoke
  "Low-level dispatch layer — encoding-aware msg-send bridge.

  Given a method spec (from [[grease.ios.registry]]) and Clojure argument
  values, [[dispatch!]] coerces the arguments, dispatches the ObjC message,
  and coerces the return value back to Clojure.

  This namespace depends on:
  - [[grease.ios.objc/msg-send]] — the actual bridge (mockable in tests)
  - [[grease.ios.types]]         — coerce-in / coerce-out / encoding-for
  - [[grease.ios.registry]]      — method-spec / class-spec
  - [[grease.ios.structs]]       — struct-return detection + pack/unpack

  Struct returns (e.g. CGPoint, CLLocationCoordinate2D) use libffi's struct
  return support via [[ffi/call-ptr]] with a [[dt-struct/define-datatype!]]
  type.  This handles both HFA (≤ 16 bytes, returned in float registers) and
  large structs (> 16 bytes, returned via hidden x8 pointer on arm64).

  Usage:
    (invoke/dispatch! receiver \"play\" method-spec [])
    (invoke/dispatch-class! \"NSMutableArray\" \"array\" method-spec [])"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as foundation]
            [grease.ios.objc :as objc]
            [grease.ios.patterns :as patterns]
            [grease.ios.structs :as structs]
            [grease.ios.types :as types]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.struct :as dt-struct]))

;; =============================================================================
;; Encoding parsing
;; =============================================================================

(def ^:private encoding-char->kw
  "Maps a single ObjC encoding character to the keyword expected by msg-send."
  {\v :void
   \@ :pointer
   \q :int64
   \Q :uint64
   \d :float64
   \f :float32
   \i :int32
   \I :uint32
   \c :int8
   \B :int8    ; BOOL — represented as int8 at the FFI layer
   \* :pointer ; C string — passed as a raw pointer
   \: :pointer ; SEL
   \^ :pointer})  ; pointer-to

(defn- ret-kw
  "Returns the msg-send return-type keyword for the given ObjC encoding string."
  [encoding]
  (get encoding-char->kw (first encoding) :pointer))

(defn- arg-kws
  "Returns a vector of msg-send type keywords for each argument in the encoding.
  Skips the first three chars: return type, self (`@`), and cmd (`:`)."
  [encoding]
  (mapv #(get encoding-char->kw % :pointer) (drop 3 encoding)))

(defn parse-encoding
  "Parses an ObjC type encoding string into a dispatch map.

  Returns ~{:ret kw :arg-types [kw ...]}~.

  Example:
    (parse-encoding \"v@:@@\") ;; -> {:ret :void :arg-types [:pointer :pointer]}"
  [encoding]
  {:ret       (ret-kw encoding)
   :arg-types (arg-kws encoding)})

;; =============================================================================
;; Struct return support (Phase 10.2.4)
;; =============================================================================

(def ^:private prim->dtype
  "Maps structs.edn primitive type strings to tech.v3.datatype dtype keywords."
  {"double" :float64 "float" :float32
   "int64"  :int64   "uint64" :uint64
   "int32"  :int32   "uint32" :uint32
   "int16"  :int16   "uint16" :uint16
   "int8"   :int8    "uint8"  :uint8})

(defn- ensure-ffi-struct!
  "Registers struct-name in the dt-struct type registry so that libffi can
  use it as a CIF return type.  Nested struct fields are registered
  recursively.  Idempotent — safe to call every dispatch."
  [struct-name]
  (when-not (dt-struct/struct-datatype? (keyword struct-name))
    (let [spec (structs/struct-for struct-name)]
      (doseq [{:keys [type]} (:fields spec)]
        (when (and (not (get prim->dtype type)) (structs/known-struct? type))
          (ensure-ffi-struct! type)))
      (dt-struct/define-datatype!
       (keyword struct-name)
       (mapv (fn [{:keys [name type]}]
               {:name     (keyword name)
                :datatype (or (get prim->dtype type) (keyword type))})
             (:fields spec))))))

(def ^:private msg-send-fptr
  "Lazy reference to objc_msgSend function pointer.
  Resolved once at first struct-return call."
  (delay (ffi/dlsym ffi/RTLD_DEFAULT (dt-ffi/string->c "objc_msgSend"))))

(defn- dt-struct->clj
  "Recursively converts a dt-struct instance to a plain Clojure keyword map.
  Nested struct fields become nested maps."
  [struct-name dt-inst]
  (let [spec (structs/struct-for struct-name)]
    (into {}
          (map (fn [{:keys [name type]}]
                 (let [kw  (keyword name)
                       val (get dt-inst kw)]
                   [kw (if (structs/known-struct? type)
                         (dt-struct->clj type val)
                         val)]))
               (:fields spec)))))

(defn- msg-send-stret!
  "Calls objc_msgSend with a struct return type via libffi struct support.
  Works for both HFA (≤ 16 bytes) and large structs (> 16 bytes) on arm64.
  typed-args is a flat seq of alternating type-kw/value pairs."
  [receiver sel-str struct-name typed-args]
  (ensure-ffi-struct! struct-name)
  (let [sel    (grease/register-objc-sel sel-str)
        result (apply ffi/call-ptr @msg-send-fptr (keyword struct-name)
                      :pointer receiver :pointer sel
                      typed-args)]
    (dt-struct->clj struct-name result)))

;; =============================================================================
;; Auto-coercion for id-typed arguments
;; =============================================================================

(defn- coerce-id-arg
  "Coerces a Clojure value to an ObjC pointer for ~id~-typed method arguments.

  Only auto-boxes primitive Clojure types that are unambiguously NOT ObjC pointers:
  - ~String~         → NSString via ~foundation/->nsstring~
  - ~Long~ / ~Integer~ → NSNumber via ~foundation/->nsnumber-long~
  - ~Double~ / ~Float~ → NSNumber via ~foundation/->nsnumber-double~

  All other values (maps, vectors, nil, raw pointers, keywords) fall through to
  the identity coercer in [[grease.ios.types/coerce-in]] for ~\"id\"~.  This
  preserves backward compatibility — mock bridge sentinel maps and raw ffi pointers
  are not touched."
  [v]
  (cond
    (string? v)  (foundation/->nsstring v)
    (integer? v) (foundation/->nsnumber-long v)
    (float? v)   (foundation/->nsnumber-double (double v))
    (number? v)  (foundation/->nsnumber-double v)   ; catches Double
    :else        (types/coerce-in "id" v)))

;; =============================================================================
;; Core dispatch
;; =============================================================================

(defn dispatch!
  "Sends an ObjC message to receiver and returns the coerced Clojure result.

  - receiver    — ObjC object pointer (instance or class)
  - sel-str     — selector string e.g. ~\"play\"~, ~\"addObject:\"~
  - method-spec — method map from [[grease.ios.registry/method-spec]]
  - arg-vals    — seq of Clojure values, one per method argument

  Coercion is driven by the method spec `:args` and `:return` fields.
  The `:encoding` field drives the msg-send wire types."
  [receiver sel-str method-spec arg-vals]
  (let [{:keys [ret arg-types]} (parse-encoding (:encoding method-spec))
        arg-specs  (:args method-spec)
        typed-args (mapcat (fn [spec kw val]
                             (let [coerced (if (:pattern spec)
                                             (patterns/wrap-arg spec val)
                                             (if (= (:type spec "id") "id")
                                               (coerce-id-arg val)
                                               (types/coerce-in (:type spec "id") val)))]
                               [kw coerced]))
                           arg-specs arg-types arg-vals)
        ret-type   (:return method-spec "id")]
    (if (structs/known-struct? ret-type)
      ;; Struct return: use libffi struct-return path (handles HFA + stret on arm64)
      (msg-send-stret! receiver sel-str ret-type typed-args)
      ;; Normal return: use objc/msg-send with coerce-out
      (let [raw-result (apply objc/msg-send ret receiver sel-str typed-args)]
        (types/coerce-out ret-type raw-result)))))

(defn dispatch-class!
  "Convenience wrapper that resolves class-name to a class pointer before
  dispatching.  Equivalent to passing ~(grease/get-objc-class class-name)~
  as receiver to [[dispatch!]]."
  [class-name sel-str method-spec arg-vals]
  (let [cls (grease/get-objc-class class-name)]
    (dispatch! cls sel-str method-spec arg-vals)))

(defn dispatch-class-raw!
  "Like [[dispatch-class!]] but skips [[types/coerce-out]] so the raw ObjC
  pointer is returned.  Use this when the caller will wrap the pointer in
  an opaque handle (e.g. [[grease.ios.api/ObjcObject]]) and does not want
  the pointer coerced to a Clojure value."
  [class-name sel-str method-spec arg-vals]
  (let [cls (grease/get-objc-class class-name)
        {:keys [ret arg-types]} (parse-encoding (:encoding method-spec))
        arg-specs  (:args method-spec)
        typed-args (mapcat (fn [spec kw val]
                             (let [coerced (if (:pattern spec)
                                             (patterns/wrap-arg spec val)
                                             (if (= (:type spec "id") "id")
                                               (coerce-id-arg val)
                                               (types/coerce-in (:type spec "id") val)))]
                               [kw coerced]))
                           arg-specs arg-types arg-vals)]
    (apply objc/msg-send ret cls sel-str typed-args)))
