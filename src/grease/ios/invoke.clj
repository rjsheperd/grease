(ns grease.ios.invoke
  "Low-level dispatch layer — encoding-aware msg-send bridge.

  Given a method spec (from [[grease.ios.registry]]) and Clojure argument
  values, [[dispatch!]] coerces the arguments, dispatches the ObjC message,
  and coerces the return value back to Clojure.

  This namespace depends on:
  - [[grease.ios.objc/msg-send]]          — the actual bridge (mockable in tests)
  - [[grease.ios.types]]                  — coerce-in / coerce-out / encoding-for
  - [[grease.ios.registry]]               — method-spec / class-spec
  - [[grease.ios.structs]]                — struct layout / pack / unpack
  - [[tech.v3.datatype.ffi]]              — string->c for msg-send function pointer
  - [[tech.v3.datatype.struct]]           — dt-struct type registry and inplace-new-struct
  - [[java.nio.ByteBuffer/allocateDirect]]— off-heap buffer for struct-arg packing

  Struct ARGUMENTS and struct RETURN types both route through [[ffi/call-ptr]]
  with composite [[dt-struct/define-datatype!]] descriptors so libffi generates
  correct ARM64 HFA calling code (d0–d3 for struct args/returns).

  Usage:
    (invoke/dispatch! receiver \"play\" method-spec [])
    (invoke/dispatch-class! \"NSMutableArray\" \"array\" method-spec [])"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as foundation]
            [grease.ios.objc :as objc]
            [grease.ios.patterns :as patterns]
            [grease.ios.structs :as structs]
            [grease.ios.types :as types]))

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

(defn- encoding-tokens
  "Splits an ObjC type encoding string into individual type tokens.
  Each token is either a single-character primitive (`d`, `B`, `@`, …) or a
  brace-delimited struct token (`{StructName=…}`).  Struct tokens may nest.

  This is necessary because the naïve char-by-char split breaks for any
  method that takes or returns a struct argument: the struct encoding
  `{MKCoordinateRegion={CLLocationCoordinate2D=dd}{MKCoordinateSpan=dd}}`
  spans many characters, so char-by-char parsing produces one token per
  character instead of one token for the whole struct."
  [encoding]
  (loop [s (seq encoding) acc [] depth 0 cur []]
    (if-not s
      (cond-> acc (seq cur) (conj (apply str cur)))
      (let [ch (first s)]
        (cond
          ;; entering a struct — start accumulating, bump depth
          (and (= ch \{) (zero? depth))
          (recur (next s) acc 1 [ch])

          ;; inside a struct — always accumulate
          (pos? depth)
          (let [new-depth (cond (= ch \{) (inc depth)
                                (= ch \}) (dec depth)
                                :else depth)]
            (if (zero? new-depth)
              ;; closing brace completes the struct token
              (recur (next s) (conj acc (apply str (conj cur ch))) 0 [])
              (recur (next s) acc new-depth (conj cur ch))))

          ;; outside a struct — each char is its own token
          :else
          (recur (next s) (conj acc (str ch)) 0 []))))))

(defn- arg-kws
  "Returns a vector of msg-send type keywords for each argument in the encoding.
  Skips the first three tokens: return type, self (`@`), and cmd (`:`).
  Struct tokens (`{…}`) map to `:pointer`; all others map via [[encoding-char->kw]]."
  [encoding]
  (mapv (fn [tok]
          (if (= (first tok) \{)
            :pointer
            (get encoding-char->kw (first tok) :pointer)))
        (drop 3 (encoding-tokens encoding))))

(defn parse-encoding
  "Parses an ObjC type encoding string into a dispatch map.

  Returns ~{:ret kw :arg-types [kw ...]}~.

  Example:
    (parse-encoding \"v@:@@\") ;; -> {:ret :void :arg-types [:pointer :pointer]}"
  [encoding]
  {:ret       (ret-kw encoding)
   :arg-types (arg-kws encoding)})

;; =============================================================================
;; Struct support — args and returns
;; =============================================================================

(def ^:private prim->dtype
  "Maps structs.edn primitive type strings to tech.v3.datatype dtype keywords."
  {"double" :float64 "float" :float32
   "int64"  :int64   "uint64" :uint64
   "int32"  :int32   "uint32" :uint32
   "int16"  :int16   "uint16" :uint16
   "int8"   :int8    "uint8"  :uint8})

(defn- ensure-ffi-struct!
  "Registers struct-name in the dt-struct type registry so libffi can build
  a composite ffi_type for it.  Nested struct fields are registered
  recursively.  Idempotent — safe to call every dispatch."
  [struct-name]
  (when-not (grease/struct-datatype? (keyword struct-name))
    (let [spec (structs/struct-for struct-name)]
      (doseq [{:keys [type]} (:fields spec)]
        (when (and (not (get prim->dtype type)) (structs/known-struct? type))
          (ensure-ffi-struct! type)))
      (grease/struct-define-datatype!
        (keyword struct-name)
        (mapv (fn [{:keys [name type]}]
                {:name     (keyword name)
                 :datatype (or (get prim->dtype type) (keyword type))})
              (:fields spec))))))

(def ^:private msg-send-fptr
  "Lazy reference to the objc_msgSend function pointer.
  Resolved once on first struct dispatch."
  (delay (ffi/dlsym ffi/RTLD_DEFAULT (grease/string->c "objc_msgSend"))))

(defn- bb-get-long
  "Reads a long from buf at absolute byte offset.
  The ^ByteBuffer type hint causes SCI to resolve getLong via
  java.nio.ByteBuffer (allPublicMethods in GraalVM reflection config)
  rather than the runtime HeapByteBuffer subclass (not in config)."
  [^java.nio.ByteBuffer buf ^long offset]
  (.getLong buf (int offset)))

(defn- clj->dt-struct
  "Converts a Clojure keyword map to a native-heap dt-struct instance for use
  as a struct-valued argument in [[ffi/call-ptr]].

  Uses [[structs/pack]] (ByteBuffer) as the layout source of truth, then
  copies the bytes into a dtype native-heap buffer and wraps it with
  [[dt-struct/inplace-new-struct]].  [[ensure-ffi-struct!]] must be called
  for struct-name before this function.

  Uses [[native-buffer/malloc]] rather than
  [[java.nio.ByteBuffer/allocateDirect]] because dtype's [[ffi/call-ptr]]
  calls [[tech.v3.datatype.protocols/PToNativeBuffer]] on struct args to
  obtain the raw memory address.  A bare DirectByteBuffer does not implement
  that protocol; a dtype NativeBuffer does.

  Copies bytes via [[native-buffer/write-long]] with raw byte offsets.
  In the GraalVM native image, malloc returns a buffer with effective
  element byte-width of 1, so write-long(i, v) writes at addr + i * 1 —
  byte offsets must be passed directly, not element indices."
  [struct-name m]
  (let [bb   (structs/pack struct-name m)
        size (:size (structs/struct-for struct-name))
        nbuf (grease/native-malloc size)]
    (.position bb 0)
    (dotimes [i (quot size 8)]
      (grease/native-write-long nbuf (* i 8) (bb-get-long bb (* i 8))))
    (grease/struct-inplace-new (keyword struct-name) nbuf)))

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
  The `:encoding` field drives the wire types.

  When any argument or the return type is a known struct, the call routes
  through [[ffi/call-ptr]] with composite ffi_type descriptors so libffi
  generates correct ARM64 HFA calling code (d0–d3 for struct args/returns).
  Struct return values come back as dtype InplaceNewStructs supporting [[get]]."
  [receiver sel-str method-spec arg-vals]
  (let [{:keys [ret arg-types]} (parse-encoding (:encoding method-spec))
        arg-specs    (:args method-spec)
        ret-type     (:return method-spec "id")
        struct-arg?  (some #(structs/known-struct? (:type % "id")) arg-specs)
        struct-ret?  (structs/known-struct? ret-type)]
    (if (or struct-arg? struct-ret?)
      ;; ── Struct path ──────────────────────────────────────────────────────────
      ;; Any struct argument or struct return type: use ffi/call-ptr so libffi
      ;; applies the correct ARM64 HFA calling convention (d0–d3 registers).
      (let [ffi-ret (if struct-ret?
                      (do (ensure-ffi-struct! ret-type)
                          (keyword ret-type))
                      ret)
            ffi-typed-args
            (mapcat (fn [spec arg-kw val]
                      (let [type-name (:type spec "id")]
                        (cond
                          (:pattern spec)
                          [arg-kw (patterns/wrap-arg spec val)]

                          (structs/known-struct? type-name)
                          (do (ensure-ffi-struct! type-name)
                              [(keyword type-name) (clj->dt-struct type-name val)])

                          (= type-name "id")
                          [arg-kw (coerce-id-arg val)]

                          :else
                          [arg-kw (types/coerce-in type-name val)])))
                    arg-specs arg-types arg-vals)
            sel    (grease/register-objc-sel sel-str)
            result (apply ffi/call-ptr @msg-send-fptr ffi-ret
                          :pointer receiver :pointer sel
                          ffi-typed-args)]
        (types/coerce-out ret-type result))

      ;; ── Fast path ────────────────────────────────────────────────────────────
      ;; No structs anywhere — use objc/msg-send directly (cheaper CIF).
      (let [typed-args
            (mapcat (fn [spec kw val]
                      (let [coerced (if (:pattern spec)
                                      (patterns/wrap-arg spec val)
                                      (if (= (:type spec "id") "id")
                                        (coerce-id-arg val)
                                        (types/coerce-in (:type spec "id") val)))]
                        [kw coerced]))
                    arg-specs arg-types arg-vals)
            raw-result (apply objc/msg-send ret receiver sel-str typed-args)]
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
