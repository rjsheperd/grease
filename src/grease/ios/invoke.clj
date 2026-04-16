(ns grease.ios.invoke
  "Low-level dispatch layer — encoding-aware msg-send bridge.

  Given a method spec (from [[grease.ios.registry]]) and Clojure argument
  values, [[dispatch!]] coerces the arguments, dispatches the ObjC message,
  and coerces the return value back to Clojure.

  This namespace depends on:
  - [[grease.ios.objc/msg-send]] — the actual bridge (mockable in tests)
  - [[grease.ios.types]]         — coerce-in / coerce-out / encoding-for
  - [[grease.ios.registry]]      — method-spec / class-spec

  Usage:
    (invoke/dispatch! receiver \"play\" method-spec [])
    (invoke/dispatch-class! \"NSMutableArray\" \"array\" method-spec [])"
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as foundation]
            [grease.ios.objc :as objc]
            [grease.ios.patterns :as patterns]
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
        raw-result (apply objc/msg-send ret receiver sel-str typed-args)
        ret-type   (:return method-spec "id")]
    (types/coerce-out ret-type raw-result)))

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
