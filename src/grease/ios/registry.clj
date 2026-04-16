(ns grease.ios.registry
  "In-memory registry of loaded framework specs.

  Provides fast lookup of classes, methods, and enum values by both ObjC and
  Clojure names.  Also maintains a retention table for ARC safety — any ObjC
  object that must outlive a single call (delegates, observers) is stored here.

  Usage:
    (registry/load-all!)                         ;; populates from spec/load-all
    (registry/class-spec \"NSString\")             ;; -> annotated class map
    (registry/method-spec \"NSString\" \"length\")  ;; -> method map
    (registry/retain!  :my-delegate ptr)         ;; -> ptr (hold in retention table)
    (registry/release! :my-delegate)"
  (:require [clojure.string :as str]
            [grease.ios.naming :as naming]
            [grease.ios.spec :as spec]
            [grease.ios.types :as types]))

;; =============================================================================
;; State atoms
;; =============================================================================

;; Map of framework-keyword -> annotated spec map.
(defonce ^:private spec-registry (atom {}))

;; Map of ObjC class name string -> annotated class map.
(defonce ^:private class-index (atom {}))

;; Map of user-supplied key -> ObjC pointer.
;; Keeps ARC objects alive beyond a single autorelease pool drain.
(defonce ^:private retention-table (atom {}))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- framework-keyword
  "Converts a framework name string to its registry keyword.
  e.g. \"Foundation\" -> :foundation"
  [name-str]
  (keyword (str/lower-case name-str)))

(defn- index-spec!
  "Annotates spec with Clojure names and inserts it into the registries."
  [spec]
  (let [annotated (naming/transform-spec spec)
        fw-key    (framework-keyword (:framework spec))]
    (swap! spec-registry assoc fw-key annotated)
    (doseq [cls (:classes annotated)]
      (swap! class-index assoc (:name cls) cls)
      (types/register-class! (:name cls)))))

;; =============================================================================
;; Init
;; =============================================================================

(defn load-all!
  "Loads all framework specs (via [[grease.ios.spec/load-all]]) and populates
  the registry.  Requires [[grease.ios.naming/init!]] to have been called first.
  Safe to call multiple times — each call rebuilds the index."
  []
  (reset! spec-registry {})
  (reset! class-index {})
  (doseq [[_ spec] (spec/load-all)]
    (index-spec! spec)))

;; =============================================================================
;; Lookups
;; =============================================================================

(defn class-spec
  "Returns the annotated class map for the given ObjC class name string,
  or nil if not found."
  [class-name]
  (get @class-index class-name))

(defn method-spec
  "Returns the annotated method map for the given ObjC class name and selector
  string, or nil if not found.  Searches both :init and :methods."
  [class-name selector]
  (when-let [cls (class-spec class-name)]
    (some #(when (= (:selector %) selector) %)
          (concat (:init cls) (:methods cls)))))

(defn enum-keyword-for
  "Returns the Clojure keyword for a raw enum integer value within the
  named enum.  Returns nil if the enum or value is not found."
  [enum-name raw]
  (some (fn [[_ spec]]
          (some (fn [e]
                  (when (= (:name e) enum-name)
                    (some (fn [v]
                            (when (= (:raw v) raw)
                              (::naming/clj-name v)))
                          (:values e))))
                (:enums spec)))
        @spec-registry))

;; =============================================================================
;; ARC retention table
;; =============================================================================

(defn retain!
  "Stores ptr in the retention table under key, preventing ARC collection.
  Returns ptr unchanged."
  [key ptr]
  (swap! retention-table assoc key ptr)
  ptr)

(defn release!
  "Removes key from the retention table, allowing ARC to collect the object."
  [key]
  (swap! retention-table dissoc key)
  nil)

(defn retained
  "Returns the pointer currently retained under key, or nil."
  [key]
  (get @retention-table key))

(defn all-specs
  "Returns the current spec-registry as a map of framework-keyword -> annotated spec."
  []
  @spec-registry)

(defn protocol-spec
  "Returns the protocol map for the given ObjC protocol name string, or nil."
  [protocol-name]
  (some (fn [[_ spec]]
          (some #(when (= (:name %) protocol-name) %)
                (:protocols spec)))
        @spec-registry))

(defn enum-raw-for
  "Returns the raw integer value for the given enum keyword within the named enum,
  or nil if not found."
  [enum-name kw]
  (some (fn [[_ spec]]
          (some (fn [e]
                  (when (= (:name e) enum-name)
                    (some (fn [v]
                            (when (= (::naming/clj-name v) kw)
                              (:raw v)))
                          (:values e))))
                (:enums spec)))
        @spec-registry))
