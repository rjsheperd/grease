(ns grease.ios.structs
  "Struct registry — loads [[resources/grease/structs.edn]] and provides
  field-layout lookups for Clojure ↔ ObjC struct coercion.

  Primitives map to ByteBuffer read/write operations:
    double → getDouble / putDouble (8 bytes)
    float  → getFloat  / putFloat  (4 bytes)
    int64  → getLong   / putLong   (8 bytes)
    int32  → getInt    / putInt    (4 bytes)
    uint32 → getInt    / putInt    (cast to int; unsigned semantics up to caller)
    uint64 → getLong   / putLong

  Usage:
    (structs/struct-for \"CGRect\")
    ;; => {:name \"CGRect\" :fields [...] :size 32}

    (structs/pack   \"CGRect\" {:x 0.0 :y 100.0 :width 375.0 :height 44.0})
    ;; => java.nio.ByteBuffer (32 bytes, direct, native byte order)

    (structs/unpack \"CGRect\" byte-buffer)
    ;; => {:origin {:x 0.0 :y 100.0} :size {:width 375.0 :height 44.0}}"
  (:require [clojure.edn :as edn]
            [grease.ios-host :as host])
  (:import (java.nio ByteBuffer ByteOrder)))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private struct-registry
  "Atom holding name → struct-spec map."
  (atom {}))

(defn- load-structs-edn []
  (let [content (host/read-resource "grease/structs.edn")]
    (when-not content
      (throw (ex-info "structs.edn not found on classpath" {})))
    (edn/read-string content)))

(defn init!
  "Loads structs.edn into the registry.  Idempotent — safe to call multiple times."
  []
  (when (empty? @struct-registry)
    (let [structs (load-structs-edn)]
      (reset! struct-registry
              (into {} (map (fn [s] [(:name s) s]) structs))))))

(defn struct-for
  "Returns the struct spec map for type-name, or nil if not known.
  Call [[init!]] before use."
  [type-name]
  (get @struct-registry type-name))

(defn known-struct?
  "Returns true if type-name refers to a known struct."
  [type-name]
  (contains? @struct-registry type-name))

;; =============================================================================
;; Primitive field I/O
;; =============================================================================

(def ^:private primitive-size
  {"double" 8 "float" 4
   "int8" 1 "int16" 2 "int32" 4 "int64" 8
   "uint8" 1 "uint16" 2 "uint32" 4 "uint64" 8})

(defn- prim-get
  "Reads a primitive value from buf at offset."
  ^double [type ^ByteBuffer buf ^long offset]
  (case type
    "double" (.getDouble buf offset)
    "float"  (.getFloat  buf offset)
    "int64"  (.getLong   buf offset)
    "uint64" (.getLong   buf offset)
    "int32"  (.getInt    buf offset)
    "uint32" (.getInt    buf offset)
    "int16"  (.getShort  buf offset)
    "uint16" (.getShort  buf offset)
    "int8"   (.get       buf offset)
    "uint8"  (.get       buf offset)
    (throw (ex-info (str "Unknown primitive type: " type) {:type type}))))

(defn- prim-put!
  "Writes a primitive value to buf at offset."
  [type ^ByteBuffer buf ^long offset val]
  (case type
    "double" (.putDouble buf offset (double val))
    "float"  (.putFloat  buf offset (float  val))
    "int64"  (.putLong   buf offset (long   val))
    "uint64" (.putLong   buf offset (long   val))
    "int32"  (.putInt    buf offset (int    val))
    "uint32" (.putInt    buf offset (int    val))
    "int16"  (.putShort  buf offset (short  val))
    "uint16" (.putShort  buf offset (short  val))
    "int8"   (.put       buf offset (byte   val))
    "uint8"  (.put       buf offset (byte   val))
    (throw (ex-info (str "Unknown primitive type: " type) {:type type}))))

;; =============================================================================
;; Pack (Clojure map → ByteBuffer)
;; =============================================================================

(declare pack)

(defn- pack-field
  "Writes a single field value into buf at field-offset."
  [field-type buf field-offset val]
  (if (get primitive-size field-type)
    (prim-put! field-type buf field-offset val)
    (let [nested-spec (struct-for field-type)]
      (when-not nested-spec
        (throw (ex-info (str "Unknown struct field type: " field-type) {:type field-type})))
      (let [nested-buf (pack field-type val)
            bytes      (byte-array (:size nested-spec))]
        (doto (.duplicate nested-buf)
          (.position 0)
          (.get bytes))
        (.position buf field-offset)
        (.put buf bytes)
        (.position buf 0)))))

(defn pack
  "Packs a Clojure map into a native-byte-order ByteBuffer for type-name.
  Nested struct fields are expected as sub-maps.
  Returns a ByteBuffer positioned at index 0."
  [type-name m]
  (let [spec (struct-for type-name)]
    (when-not spec
      (throw (ex-info (str "Unknown struct: " type-name) {:type type-name})))
    (let [buf (-> (ByteBuffer/allocate (:size spec))
                  (.order (ByteOrder/nativeOrder)))]
      (doseq [{:keys [name type offset]} (:fields spec)]
        (let [kw  (keyword name)
              val (get m kw)]
          (when (some? val)
            (pack-field type buf offset val))))
      (.position buf 0)
      buf)))

;; =============================================================================
;; Unpack (ByteBuffer → Clojure map)
;; =============================================================================

(declare unpack)

(defn- unpack-field
  "Reads a single field value from buf at field-offset."
  [field-type buf field-offset]
  (if (get primitive-size field-type)
    (prim-get field-type buf field-offset)
    (let [nested-spec (struct-for field-type)]
      (when-not nested-spec
        (throw (ex-info (str "Unknown struct field type: " field-type) {:type field-type})))
      (let [nested-buf (-> (ByteBuffer/allocate (:size nested-spec))
                           (.order (ByteOrder/nativeOrder)))
            bytes      (byte-array (:size nested-spec))]
        (.position buf field-offset)
        (.get buf bytes)
        (.position buf 0)
        (.put nested-buf bytes)
        (.position nested-buf 0)
        (unpack field-type nested-buf)))))

(defn unpack
  "Unpacks a ByteBuffer into a Clojure map for type-name.
  Nested struct fields become nested maps.
  Returns a keyword-keyed map."
  [type-name ^ByteBuffer buf]
  (let [spec (struct-for type-name)]
    (when-not spec
      (throw (ex-info (str "Unknown struct: " type-name) {:type type-name})))
    (into {}
          (map (fn [{:keys [name type offset]}]
                 [(keyword name) (unpack-field type buf offset)])
               (:fields spec)))))
