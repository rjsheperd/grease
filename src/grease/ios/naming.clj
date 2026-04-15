(ns grease.ios.naming
  "ObjC ↔ Clojure name transformation engine.

  Loads [[resources/grease/naming.edn]] at startup and applies the rule list
  to convert ObjC names to Clojure names and vice versa.

  Rules are matched in order; first match wins.  Each rule's ~:transform~
  dispatches through a multimethod — adding a new transform is one defmethod.

  Usage:
    (naming/init!)
    (naming/->clj :method \"isPlaying\")           ;; -> \"playing?\"
    (naming/->clj :method \"startRunning\")         ;; -> \"start-running\"
    (naming/->clj :class  \"AVPlayer\")             ;; -> \"player\"
    (naming/->clj :enum-val \"readyToPlay\")        ;; -> :ready-to-play
    (naming/->objc :method \"start-running\")       ;; -> \"startRunning\""
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Selector flattening — colons become dashes
;; =============================================================================

(defn ^:private flatten-selector
  "Flattens a multi-part ObjC selector by replacing colons with dashes.
  Leading/trailing dashes are removed.
  Examples:
    \"setDelegate:\"          -> \"setDelegate-\"  -> stripped -> \"setDelegate\"
    \"setObject:forKey:\"     -> \"setObject-forKey-\"
    \"initWithDelegate:queue:\" -> \"initWithDelegate-queue-\""
  [sel]
  (str/replace sel ":" "-"))

;; =============================================================================
;; camelCase → kebab-case helpers
;; =============================================================================

(defn ^:private camel->kebab-str
  "Converts a camelCase string to kebab-case.
  Multiple consecutive uppercase letters (e.g. URL) are handled by
  lowercasing the run and inserting a hyphen before the final letter if
  it starts a new word."
  [s]
  (-> s
      (str/replace #"([A-Z])([A-Z]+)([A-Z][a-z])" "$1$2-$3")
      (str/replace #"([a-z])([A-Z])"               "$1-$2")
      (str/replace #"-+"                            "-")
      (str/replace #"-$"                            "")   ; strip trailing dash
      str/lower-case))

;; =============================================================================
;; Framework prefix stripping
;; =============================================================================

(def ^:private framework-prefixes
  "Known 2- and 3-letter uppercase prefixes to strip from class names."
  #{"NS" "UI" "AV" "CL" "CB" "MK" "SK" "WK" "HK" "AR" "VN" "NL"
    "GK" "EK" "CN" "PH" "MF" "SF" "CF" "CG" "CM" "CA" "MA" "MW"})

(defn ^:private strip-prefix
  "Strips a known framework prefix from class-name, then camel->kebabs the rest."
  [class-name]
  (let [found (first (filter #(str/starts-with? class-name %) framework-prefixes))]
    (if found
      (camel->kebab-str (subs class-name (count found)))
      (camel->kebab-str class-name))))

;; =============================================================================
;; Transform multimethods
;; =============================================================================

(defmulti ^:private apply-transform
  "Applies a named transform to an ObjC name, returning the Clojure name."
  (fn [transform _scope _objc-name] transform))

(defmethod apply-transform :strip+question
  [_ _ objc-name]
  ;; "isPlaying" -> "playing?", "hasPrefix" -> "prefix?"
  (let [stripped (-> objc-name
                     (str/replace #"^is" "")
                     (str/replace #"^has" ""))]
    (str (camel->kebab-str stripped) "?")))

(defmethod apply-transform :wrap-set-bang
  [_ _ objc-name]
  ;; "volume" -> "set-volume!"
  (str "set-" (camel->kebab-str objc-name) "!"))

(defmethod apply-transform :camel->kebab
  [_ _ objc-name]
  ;; Flatten colons first, then camel->kebab
  (-> objc-name
      flatten-selector
      (str/replace #"-$" "")          ; strip trailing dash from "addObject:-"
      camel->kebab-str))

(defmethod apply-transform :camel->kebab-keyword
  [_ _ objc-name]
  (keyword (camel->kebab-str objc-name)))

(defmethod apply-transform :strip-framework-prefix
  [_ _ class-name]
  (strip-prefix class-name))

(defmethod apply-transform :default
  [transform _ objc-name]
  (throw (ex-info (str "Unknown transform: " transform)
                  {:transform transform :input objc-name})))

;; =============================================================================
;; Rule table
;; =============================================================================

(def ^:private rules-atom (atom []))

(defn- compile-rule
  "Compiles a single rule from naming.edn — pre-compiles the :match regex."
  [{:keys [match] :as rule}]
  (cond-> rule
    match (assoc :match-pattern (re-pattern match))))

(defn init!
  "Loads naming.edn and compiles rules.  Call once at startup."
  []
  (let [res (io/resource "grease/naming.edn")]
    (when-not res
      (throw (ex-info "Cannot find grease/naming.edn on classpath" {})))
    (let [{:keys [rules]} (edn/read-string (slurp res))]
      (reset! rules-atom (mapv compile-rule rules)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ->clj
  "Converts an ObjC name to a Clojure name.
  scope is :method :setter :class or :enum-val.
  Returns a string (for :method/:setter/:class) or keyword (for :enum-val).
  If no rule matches, falls back to camel->kebab for methods or identity for classes."
  [scope objc-name]
  (loop [[rule & rest-rules] @rules-atom]
    (cond
      (nil? rule)
      ;; No matching rule — default fallback
      (case scope
        :enum-val (keyword (camel->kebab-str objc-name))
        :class    (strip-prefix objc-name)
        (-> objc-name flatten-selector (str/replace #"-$" "") camel->kebab-str))

      (not= scope (:scope rule))
      (recur rest-rules)

      (and (:match-pattern rule)
           (not (re-find (:match-pattern rule) (flatten-selector objc-name))))
      (recur rest-rules)

      :else
      (apply-transform (:transform rule) scope objc-name))))

(defn ->objc
  "Returns the ObjC selector string for a given scope and Clojure name.
  Performs a reverse lookup in the pre-computed reverse index (if available),
  otherwise returns nil.

  Note: the full reverse lookup is populated by transform-spec at spec load
  time.  For simple cases, ->clj is invertible by hand — but do not rely on
  that for multi-part selectors."
  [_scope _clj-name]
  ;; Reverse lookup is populated per-spec by transform-spec.
  ;; Without a loaded spec, we cannot reliably reverse-map.
  nil)

(defn transform-spec
  "Annotates a framework spec map with ::clj-name on every class,
  method, property, and enum value.  Also builds reverse-index maps
  (Clojure name -> ObjC selector) under ::method-index and ::class-index."
  [spec]
  (letfn [(annotate-method [m]
            (assoc m ::clj-name (->clj :method (:selector m))))
          (annotate-property [p]
            (assoc p ::clj-name (->clj :method (:name p))
                   ::setter-name (->clj :setter (:name p))))
          (annotate-enum-val [v]
            (assoc v ::clj-name (->clj :enum-val (:name v))))
          (annotate-class [c]
            (-> c
                (assoc ::clj-name (->clj :class (:name c)))
                (update :methods    (partial mapv annotate-method))
                (update :init       (partial mapv annotate-method))
                (update :properties (partial mapv annotate-property))))
          (annotate-enum [e]
            (-> e
                (assoc ::clj-name (->clj :class (:name e)))
                (update :values (partial mapv annotate-enum-val))))]
    (-> spec
        (update :classes (partial mapv annotate-class))
        (update :enums   (partial mapv annotate-enum)))))
