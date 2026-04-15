(ns grease.ios.spec
  "EDN spec loader and validator for grease.ios API specs.

  Reads framework spec files from ~resources/grease/api-specs/~ (enumerated
  via ~resources/grease/api-specs/manifest.edn~) and validates their structure.

  Usage:
    (spec/load-all)           ;; -> {\"Foundation\" spec-map ...}
    (spec/load-one path)      ;; -> validated spec map
    (spec/validate spec)      ;; -> spec, or throws ex-info on failure"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; =============================================================================
;; Structural validation
;; =============================================================================

(defn- validate-method
  "Throws ex-info if m is missing required method keys."
  [m ctx]
  (when-not (string? (:selector m))
    (throw (ex-info (str ctx ": method missing :selector string") {:method m})))
  (when-not (string? (:encoding m))
    (throw (ex-info (str ctx ": method missing :encoding string") {:method m})))
  (when-not (vector? (:args m))
    (throw (ex-info (str ctx ": method missing :args vector") {:method m})))
  m)

(defn- validate-class
  "Throws ex-info if cls is missing required class keys."
  [cls framework]
  (when-not (string? (:name cls))
    (throw (ex-info (str framework ": class missing :name string") {:class cls})))
  (let [ctx (str framework "/" (:name cls))]
    (when-not (vector? (:init cls))
      (throw (ex-info (str ctx ": :init must be a vector") {:class cls})))
    (when-not (vector? (:methods cls))
      (throw (ex-info (str ctx ": :methods must be a vector") {:class cls})))
    (doseq [m (concat (:init cls) (:methods cls))]
      (validate-method m ctx)))
  cls)

(defn validate
  "Validates a parsed spec map structurally.
  Returns spec unchanged, or throws [[clojure.lang.ExceptionInfo]] on failure.
  Does not perform type resolution (that requires the type registry to be loaded)."
  [spec]
  (when-not (string? (:framework spec))
    (throw (ex-info "Spec missing :framework string" {:spec spec})))
  (when-not (vector? (:classes spec))
    (throw (ex-info (str (:framework spec) ": :classes must be a vector") {:spec spec})))
  (when-not (vector? (:enums spec))
    (throw (ex-info (str (:framework spec) ": :enums must be a vector") {:spec spec})))
  (doseq [cls (:classes spec)]
    (validate-class cls (:framework spec)))
  spec)

;; =============================================================================
;; Public API
;; =============================================================================

(defn load-one
  "Reads and validates a single spec EDN file from the classpath.
  path must be a classpath-relative string, e.g. ~\"grease/api-specs/Foundation.edn\"~.
  Returns the validated spec map, or throws if missing or structurally invalid."
  [path]
  (let [res (io/resource path)]
    (when-not res
      (throw (ex-info (str "Cannot find spec on classpath: " path) {:path path})))
    (-> (slurp res)
        edn/read-string
        validate)))

(defn load-all
  "Reads ~resources/grease/api-specs/manifest.edn~ and loads every spec listed.
  Returns a map of framework-name-string -> validated spec map."
  []
  (let [manifest-res (io/resource "grease/api-specs/manifest.edn")]
    (when-not manifest-res
      (throw (ex-info "Cannot find grease/api-specs/manifest.edn on classpath" {})))
    (let [{:keys [specs]} (edn/read-string (slurp manifest-res))]
      (into {}
            (map (fn [path]
                   (let [spec (load-one path)]
                     [(:framework spec) spec]))
                 specs)))))
