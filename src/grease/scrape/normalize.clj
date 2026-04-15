(ns grease.scrape.normalize
  "Converts Apple Developer docs JSON into grease EDN spec maps.

  Apple docs fragments contain tokenized ObjC method declarations:
    [{:kind \"text\"           :text \"- (\"}
     {:kind \"typeIdentifier\" :text \"void\"}
     {:kind \"text\"           :text \") \"}
     {:kind \"identifier\"     :text \"startUpdatingLocation\"}]

  This namespace extracts :selector, :return, :encoding, and :args
  from those fragments and produces maps that pass [[grease.ios.spec/validate]].

  Limitations (v1):
  - Protocol-typed args (e.g. id<Delegate>) may default to \\\"id\\\"
  - Struct-returning methods are not auto-tagged :unsupported; callers must
    post-process if needed
  - Deprecation tags require a separate pass over Apple's availability info

  Usage:
    (normalize-method-ref ref-map)     ;; -> grease method map
    (normalize-class-doc doc)          ;; -> grease class spec map
    (framework->edn \\\"CoreLocation\\\")   ;; -> full EDN spec map"
  (:require [clojure.string :as str]
            [grease.scrape.apple-docs :as apple-docs]))

;; =============================================================================
;; ObjC type → grease type mapping
;; =============================================================================

(def ^:private objc->grease
  "Maps common ObjC / C type names from Apple docs to grease type names
  (as defined in resources/grease/types.edn) and their single-char encodings."
  {"void"              {:type "void"         :enc "v"}
   "id"                {:type "id"           :enc "@"}
   "instancetype"      {:type "id"           :enc "@"}
   "BOOL"              {:type "BOOL"         :enc "B"}
   "NSInteger"         {:type "NSInteger"    :enc "q"}
   "NSUInteger"        {:type "NSUInteger"   :enc "Q"}
   "int"               {:type "NSInteger"    :enc "q"}
   "NSTimeInterval"    {:type "Double"       :enc "d"}
   "CGFloat"           {:type "Double"       :enc "d"}
   "double"            {:type "Double"       :enc "d"}
   "float"             {:type "Float"        :enc "f"}
   "NSString"          {:type "NSString"     :enc "@"}
   "NSMutableString"   {:type "NSString"     :enc "@"}
   "NSArray"           {:type "NSArray"      :enc "@"}
   "NSMutableArray"    {:type "NSArray"      :enc "@"}
   "NSDictionary"      {:type "NSDictionary" :enc "@"}
   "NSMutableDictionary" {:type "NSDictionary" :enc "@"}
   "NSURL"             {:type "NSURL"        :enc "@"}
   "NSError"           {:type "NSError"      :enc "@"}
   "NSNumber"          {:type "NSNumber"     :enc "@"}
   "dispatch_queue_t"  {:type "id"           :enc "@"}})

(def ^:private fallback-type {:type "id" :enc "@"})

(defn- resolve-type
  "Returns the grease {:type ... :enc ...} map for an ObjC type name.
  Falls back to id/@ for unrecognised types."
  [objc-name]
  (get objc->grease (str/trim (or objc-name "")) fallback-type))

;; =============================================================================
;; Fragment parsing
;; =============================================================================

(defn- method-kind
  "Returns :class or :instance based on the leading text fragment.
  Returns :unknown if the kind cannot be determined."
  [fragments]
  (let [first-text (-> fragments first :text (or ""))]
    (cond
      (str/starts-with? first-text "+ ") :class
      (str/starts-with? first-text "- ") :instance
      :else                               :unknown)))

(defn- selector-from-fragments
  "Concatenates all :identifier kind tokens to produce the ObjC selector."
  [fragments]
  (->> fragments
       (filter #(= "identifier" (:kind %)))
       (map :text)
       str/join))

(defn- type-identifiers
  "Returns a seq of ObjC type name strings from all :typeIdentifier tokens."
  [fragments]
  (->> fragments
       (filter #(= "typeIdentifier" (:kind %)))
       (map :text)))

(defn- arg-names-from-fragments
  "Returns a seq of arg name strings from all :internalParam tokens."
  [fragments]
  (->> fragments
       (filter #(= "internalParam" (:kind %)))
       (map :text)))

(defn- n-args
  "Counts the number of arguments by counting colons in the selector."
  [selector]
  (count (filter #(= \: %) selector)))

;; =============================================================================
;; Encoding builder
;; =============================================================================

(defn build-encoding
  "Builds an ObjC type encoding string from return-type name and arg-type names.

  Format: {return-char}@:{arg-chars...}
  Example: (build-encoding \"void\" [\"NSString\"]) => \"v@:@\""
  [return-type-name arg-type-names]
  (let [ret-char  (:enc (resolve-type return-type-name))
        arg-chars (map #(:enc (resolve-type %)) arg-type-names)]
    (str ret-char "@:" (str/join arg-chars))))

;; =============================================================================
;; Method reference normalization
;; =============================================================================

(defn normalize-method-ref
  "Converts one Apple docs reference map into a grease method spec map.

  The reference-map should have :title, :role, and :fragments keys.
  Returns a map with :selector, :encoding, :return, :args.

  Heuristic for arg types:
  - Counts args via colons in the selector
  - Takes the first N typeIdentifier tokens after the return type
  - This may produce incorrect types for protocol-typed args (e.g. id<Delegate>);
    those will default to \\\"id\\\" which is structurally valid"
  [reference-map]
  (let [frags      (or (:fragments reference-map) [])
        selector   (or (:title reference-map)
                       (selector-from-fragments frags))
        type-ids   (type-identifiers frags)
        ret-name   (or (first type-ids) "id")
        n          (n-args selector)
        arg-names  (arg-names-from-fragments frags)
        ;; Skip the first typeId (return type); take next n for args.
        ;; Pad with "id" if fragment data is incomplete — do NOT vec the padded
        ;; infinite seq; take n first to avoid OOM.
        raw-arg-types (vec (take n (rest type-ids)))
        arg-types     (vec (take n (concat raw-arg-types (repeat "id"))))
        args       (mapv (fn [i]
                           {:name (or (nth arg-names i nil)
                                      (str "arg" i))
                            :type (:type (resolve-type (nth arg-types i "id")))})
                         (range n))]
    {:selector selector
     :encoding (build-encoding ret-name arg-types)
     :return   (:type (resolve-type ret-name))
     :args     args}))

;; =============================================================================
;; Class document normalization
;; =============================================================================

(defn- init-selector?
  "Returns true if a selector belongs in the :init list.
  Class methods (+) and instance methods whose selector starts with \"init\"
  or is exactly \"new\" or \"alloc\" go in :init."
  [selector kind]
  (or (= :class kind)
      (str/starts-with? selector "init")
      (= "new" selector)
      (= "alloc" selector)))

(defn normalize-class-doc
  "Converts a class documentation JSON map (as returned by apple-docs/class-doc)
  into a grease class spec map.

  class-name is used as :name. superclass defaults to \\\"NSObject\\\".

  Method references with role \\\"symbol\\\" are normalised; the class's own
  self-reference (whose title == class-name) is excluded."
  [doc class-name]
  (let [all-refs  (->> (:references doc)
                       vals
                       (filter #(= "symbol" (:role %)))
                       (remove #(= class-name (:title %))))
        methods   (mapv (fn [ref]
                          (let [frags (:fragments ref)
                                sel   (or (:title ref) (selector-from-fragments frags))
                                kind  (method-kind frags)
                                m     (normalize-method-ref ref)]
                            (assoc m :_kind kind :_sel sel)))
                        all-refs)
        inits     (->> methods
                       (filter #(init-selector? (:_sel %) (:_kind %)))
                       (mapv #(dissoc % :_kind :_sel)))
        instances (->> methods
                       (remove #(init-selector? (:_sel %) (:_kind %)))
                       (mapv #(dissoc % :_kind :_sel)))]
    {:name       class-name
     :superclass "NSObject"
     :init       inits
     :methods    instances
     :properties []}))

;; =============================================================================
;; Framework assembly
;; =============================================================================

(defn framework->edn
  "Fetches Apple docs JSON for framework-name and normalises it into a
  grease EDN spec map that passes grease.ios.spec/validate.

  framework-name: case-insensitive, e.g. \\\"CoreLocation\\\"

  Only classes whose class-doc page is accessible are included.
  Protocols and enums are not auto-scraped in v1 — add them by hand."
  [framework-name]
  (let [sym-refs   (apple-docs/framework-symbols framework-name)
        class-refs (->> sym-refs
                        (remove #(str/includes? (or (:title %) "") ":")))
        classes    (into []
                         (keep (fn [ref]
                                 (let [cn  (:title ref)
                                       doc (apple-docs/class-doc framework-name cn)]
                                   (when doc
                                     (normalize-class-doc doc cn))))
                               class-refs))]
    {:framework  (str/capitalize framework-name)
     :doc-url    (str "https://developer.apple.com/documentation/"
                      (str/lower-case framework-name))
     :classes    classes
     :protocols  []
     :enums      []
     :constants  []}))
