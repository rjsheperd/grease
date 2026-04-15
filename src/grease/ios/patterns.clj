(ns grease.ios.patterns
  "Bridge between Clojure idioms and ObjC cross-boundary patterns.

  Currently implements the ~:delegate~ pattern:
  - User passes a ~{:keyword fn}~ map (selectors in Clojure kebab-case).
  - Engine looks up the ObjC protocol methods from the loaded spec.
  - An ObjC subclass of NSObject is created via [[create-delegate-class!]].
  - The class is retained by a hash of the map so the same map always
    returns the same delegate (idempotent across multiple calls).

  Usage:
    (patterns/wrap-delegate {:location-manager-did-update-locations my-fn}
                            \"CLLocationManagerDelegate\")
    ;; -> ObjC delegate instance pointer

  Mocking in tests:
    (with-redefs [grease.ios.patterns/create-delegate-class!
                  (fn [_name _super _pairs] {:mock-delegate true})]
      ...)"
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.naming :as naming]
            [grease.ios.registry :as registry]))

;; =============================================================================
;; Encoding helpers (self-contained — no circular dep on grease.ios.invoke)
;; =============================================================================

(def ^:private encoding-char->kw
  {\v :void
   \@ :pointer
   \q :int64
   \Q :uint64
   \d :float64
   \f :float32
   \i :int32
   \I :uint32
   \c :int8
   \B :int8
   \* :pointer
   \: :pointer
   \^ :pointer})

(defn- enc->ret-kw [encoding]
  (get encoding-char->kw (first encoding) :pointer))

(defn- enc->arg-kws [encoding]
  (mapv #(get encoding-char->kw % :pointer) (drop 3 encoding)))

;; =============================================================================
;; ObjC class creation
;; =============================================================================

(defn create-delegate-class!
  "Creates an ObjC subclass of superclass with the given selector/encoding/fn triples.
  This is the single mockable entry point for delegate class creation.

  class-name       — unique ObjC class name string
  superclass       — ObjC superclass name string e.g. ~\"NSObject\"~
  selector-enc-fns — seq of ~[selector-string encoding-string fn]~ triples

  Returns the new Class pointer."
  [class-name superclass selector-enc-fns]
  (let [cls (grease/allocate-objc-class!
             class-name
             (grease/get-objc-class superclass))]
    (doseq [[sel-str enc f] selector-enc-fns]
      (let [arg-kws  (enc->arg-kws enc)
            ret-kw   (enc->ret-kw enc)
            imp      (grease/make-imp f arg-kws ret-kw)
            sel      (grease/register-objc-sel sel-str)]
        (grease/add-objc-method! cls sel imp enc)))
    (grease/register-objc-class! cls)
    cls))

;; =============================================================================
;; Delegate wrapping (idempotent)
;; =============================================================================

(defn- clj-name->selector
  "Converts a Clojure keyword name to an ObjC selector by looking up the
  protocol's method list.  Falls back to the raw kebab→camelCase if not found."
  [kw protocol-methods]
  (let [kw-str (name kw)]
    (some #(when (= (::naming/clj-name %) kw-str) (:selector %))
          protocol-methods)))

(defn wrap-delegate
  "Converts a Clojure map of ~{:selector-keyword fn}~ to an ObjC delegate proxy.

  Idempotent: the same map (by ~hash~) always returns the same delegate object.
  The delegate is retained in the registry's retention table.

  delegate-map  — ~{:did-update-locations fn :did-fail-with-error fn ...}~
  protocol-name — ObjC protocol name e.g. ~\"CLLocationManagerDelegate\"~

  Returns an ObjC instance pointer (Class pointer allocated via [[create-delegate-class!]]
  and instantiated with ~+new~)."
  [delegate-map protocol-name]
  (let [retention-key [::delegate (hash delegate-map) protocol-name]
        cached        (registry/retained retention-key)]
    (if cached
      cached
      (let [proto-spec (registry/protocol-spec protocol-name)
            proto-methods (if proto-spec (:methods proto-spec) [])
            class-name  (str "GrseDelegate" (Math/abs (int (hash delegate-map))))
            sel-enc-fns (keep (fn [[kw f]]
                                (when-let [sel (clj-name->selector kw proto-methods)]
                                  (let [m (some #(when (= (:selector %) sel) %) proto-methods)]
                                    [sel (:encoding m "v@:@@") f])))
                              delegate-map)
            cls  (create-delegate-class! class-name "NSObject" sel-enc-fns)
            inst (grease/objc-new cls)]
        (registry/retain! retention-key inst)
        inst))))

;; =============================================================================
;; wrap-arg — dispatches pattern from arg-spec
;; =============================================================================

(defmulti ^:private wrap-arg*
  "Dispatches on the :pattern key of an arg-spec map."
  (fn [arg-spec _val] (:pattern arg-spec)))

(defmethod wrap-arg* :delegate
  [arg-spec delegate-map]
  (wrap-delegate delegate-map (:protocol arg-spec "NSObject")))

(defmethod wrap-arg* :default
  [_ val]
  val)

(defn wrap-arg
  "Applies the pattern-specific wrapper to val if arg-spec has a :pattern key.
  Returns val unchanged for plain typed args."
  [arg-spec val]
  (if (:pattern arg-spec)
    (wrap-arg* arg-spec val)
    val))