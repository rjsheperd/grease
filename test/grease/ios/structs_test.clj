(ns grease.ios.structs-test
  "Validates resources/grease/structs.edn — struct field layouts for iOS arm64.

  Checks:
  1. The file loads as a valid EDN vector.
  2. Every struct has :name, :fields, and :size.
  3. Every field has :name, :type, and :offset.
  4. Every field :type is either a known primitive or the :name of another struct.
  5. :size matches the last field's offset + that field's primitive size (sanity check)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private primitive-types
  #{"double" "float" "int8" "int16" "int32" "int64" "uint8" "uint16" "uint32" "uint64"})

(def ^:private primitive-sizes
  {"double" 8 "float" 4
   "int8"  1 "int16" 2 "int32" 4 "int64" 8
   "uint8" 1 "uint16" 2 "uint32" 4 "uint64" 8})

(defn- load-structs []
  (-> "grease/structs.edn" io/resource slurp edn/read-string))

;; =============================================================================
;; 1. File loads
;; =============================================================================

(deftest ^:parallel structs-edn-loads-test
  (testing "structs.edn exists and parses as a non-empty vector"
    (let [structs (load-structs)]
      (is (vector? structs))
      (is (pos? (count structs))))))

;; =============================================================================
;; 2 & 3. Required keys on every struct and field
;; =============================================================================

(deftest ^:parallel structs-required-keys-test
  (testing "every struct has :name, :fields, :size; every field has :name, :type, :offset"
    (doseq [{:keys [name fields size] :as s} (load-structs)]
      (is (string? name)  (str "struct :name missing in " s))
      (is (vector? fields) (str name " :fields is not a vector"))
      (is (integer? size)  (str name " :size is not an integer"))
      (doseq [{:keys [name type offset] :as f} fields]
        (is (string? name)   (str "field :name missing in " f))
        (is (string? type)   (str "field :type missing in " f))
        (is (integer? offset) (str "field :offset is not an integer in " f))))))

;; =============================================================================
;; 4. Field types are primitive or known struct names
;; =============================================================================

(deftest ^:parallel field-types-resolve-test
  (testing "every field :type resolves to a primitive or another struct :name"
    (let [structs   (load-structs)
          all-names (set (map :name structs))
          valid?    (fn [t] (or (contains? primitive-types t)
                                (contains? all-names t)))]
      (doseq [{struct-name :name :keys [fields]} structs
              {field-name :name :keys [type]}    fields]
        (is (valid? type)
            (str "Unresolved field type: " type " in " struct-name "/" field-name))))))

;; =============================================================================
;; 5. Size sanity: last-field offset + last-field size == struct size
;; =============================================================================

(defn- field-size
  "Returns the byte size of a field given the struct lookup map."
  [type struct-map]
  (if-let [prim (get primitive-sizes type)]
    prim
    (:size (get struct-map type))))

(deftest ^:parallel struct-size-sanity-test
  (testing "struct :size equals last-field offset + last-field's own size"
    (let [structs    (load-structs)
          struct-map (into {} (map (fn [s] [(:name s) s]) structs))]
      (doseq [{:keys [name fields size]} structs]
        (when (seq fields)
          (let [last-f      (last fields)
                last-size   (field-size (:type last-f) struct-map)
                expected    (+ (:offset last-f) (or last-size 0))]
            (is (= size expected)
                (str name ": expected :size " expected " got " size))))))))
