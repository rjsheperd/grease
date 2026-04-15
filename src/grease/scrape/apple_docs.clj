(ns grease.scrape.apple-docs
  "Apple Developer documentation JSON fetcher with file-based caching.

  Fetches framework and class documentation from:
    https://developer.apple.com/tutorials/data/documentation/<path>.json

  Responses are cached to [[*cache-dir*]] (default: `.cache/apple-docs/`).
  Bind [[*cache-dir*]] in tests to point at fixture files — no network required.

  Usage:
    (fetch \"corelocation\")
    (fetch \"corelocation/cllocationmanager\")
    (framework-symbols \"CoreLocation\")
    (class-doc \"CoreLocation\" \"CLLocationManager\")"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *cache-dir*
  "Root directory for cached Apple docs JSON files.
  Bind to a fixture directory in tests to avoid network calls."
  ".cache/apple-docs")

(def ^:private ^String base-url
  "https://developer.apple.com/tutorials/data/documentation")

;; =============================================================================
;; Cache utilities (private)
;; =============================================================================

(defn- cache-file
  "Returns the cache java.io.File for doc-path (relative to [[*cache-dir*]])."
  ^java.io.File [doc-path]
  (io/file *cache-dir* (str doc-path ".json")))

(defn- ensure-parent!
  "Creates parent directories of f if they do not exist."
  [^java.io.File f]
  (some-> (.getParentFile f) .mkdirs))

(defn- parse-json
  "Parses a JSON string to a Clojure map with keyword keys."
  [^String s]
  (json/read-str s :key-fn keyword))

;; =============================================================================
;; Remote fetch (extracted for test redefinition)
;; =============================================================================

(defn ^:private fetch-remote
  "Fetches a URL and returns the raw body string.
  Separated from [[fetch]] so tests can redef it without mocking `slurp`."
  [^String url]
  (slurp url))

;; =============================================================================
;; Public API — fetch
;; =============================================================================

(defn fetch
  "Fetches Apple docs JSON for doc-path and returns a parsed Clojure map.

  doc-path is relative, e.g.:
    \"corelocation\"
    \"corelocation/cllocationmanager\"
    \"avfoundation/avcapturedevice\"

  Cache strategy:
  - If [[*cache-dir*]]/<doc-path>.json exists, reads from disk (no network).
  - Otherwise fetches from Apple, writes body to cache, then returns parsed map.

  Bind [[*cache-dir*]] in tests to a directory of pre-populated fixture files
  to run fully offline."
  [doc-path]
  (let [f (cache-file doc-path)]
    (if (.exists f)
      (parse-json (slurp f))
      (let [url  (str base-url "/" doc-path ".json")
            body (fetch-remote url)]
        (ensure-parent! f)
        (spit f body)
        (parse-json body)))))

;; =============================================================================
;; Public API — framework-level queries
;; =============================================================================

(defn framework-symbols
  "Returns a sequence of reference maps for all symbols in a framework's
  top-level Apple docs JSON.  Only references with `:role \"symbol\"` are
  included (collection and article references are filtered out).

  framework-name: case-insensitive, e.g. \"CoreLocation\" or \"corelocation\"."
  [framework-name]
  (->> (fetch (str/lower-case framework-name))
       :references
       vals
       (filter #(= "symbol" (:role %)))))

(defn class-identifiers
  "Returns a seq of Apple doc identifier strings for top-level class references
  in a framework.  Filters out method references (those whose `:title` contains
  a colon, which indicates a multi-part ObjC selector).

  framework-name: case-insensitive."
  [framework-name]
  (->> (fetch (str/lower-case framework-name))
       :references
       (keep (fn [[k v]]
               (when (and (= "symbol" (:role v))
                          (not (str/includes? (or (:title v) "") ":")))
                 (name k))))))

;; =============================================================================
;; Public API — class-level queries
;; =============================================================================

(defn class-doc
  "Returns the parsed Apple docs JSON map for a specific class page, or nil.

  framework-name: case-insensitive, e.g. \"CoreLocation\"
  class-name: case-insensitive, e.g. \"CLLocationManager\""
  [framework-name class-name]
  (try
    (fetch (str (str/lower-case framework-name)
                "/"
                (str/lower-case class-name)))
    (catch Exception _
      nil)))

(defn class-methods
  "Returns a sequence of symbol reference maps from a class's documentation page.
  Each map has at least :title, :role, :fragments keys (from the Apple JSON).
  Returns nil if the class page cannot be fetched."
  [framework-name class-name]
  (when-let [doc (class-doc framework-name class-name)]
    (->> (:references doc)
         vals
         (filter #(= "symbol" (:role %))))))

(defn fragment-text
  "Concatenates the :text strings from a reference's :fragments list.
  Useful for reconstructing the ObjC declaration string.

  Example:
    (fragment-text {:fragments [{:kind \"text\" :text \"- \"}
                                {:kind \"identifier\" :text \"startUpdatingLocation\"}]})
    ;; => \"- startUpdatingLocation\""
  [reference]
  (->> (:fragments reference)
       (map :text)
       (apply str)))
