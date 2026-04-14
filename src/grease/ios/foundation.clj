(ns grease.ios.foundation
  "Bidirectional Clojure ↔ Foundation type converters.

  Converts NSString, NSArray, NSDictionary, NSNumber, and NSError between
  their ObjC pointer representations and Clojure values.

  All functions operate on raw ObjC object pointers as returned by
  [[com.phronemophobic.grease/get-objc-class]] and [[grease.ios.objc/msg-send]].

  Autorelease caution: `->nsstring`, `->nsarray`, and `->nsdict` return
  autoreleased objects. Retain them (hold in an atom, or pass to a
  method that retains internally) to keep them alive beyond the current
  autorelease pool drain.

  Null pointer caution: clj-libffi rejects `java.lang.Long` for `:pointer`
  arguments. To pass a nil ObjC object, use an empty collection or the
  `NSNull` singleton:
    `(msg-send :pointer (get-class \"NSNull\") \"null\")`"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [tech.v3.datatype.ffi :as dt-ffi]))

(defn- ^:private msg-send*
  "Internal ObjC message send; avoids a circular dependency on grease.ios.objc."
  [ret-type obj sel-str & typed-args]
  (let [sel (grease/register-objc-sel sel-str)]
    (apply ffi/call "objc_msgSend" ret-type
           :pointer obj :pointer sel
           typed-args)))

;; =============================================================================
;; NSString
;; =============================================================================

(defn nsstring->str
  "Returns the UTF-8 Clojure string contents of an NSString pointer."
  [ptr]
  (let [c-ptr (msg-send* :pointer ptr "UTF8String")]
    (dt-ffi/c->string c-ptr)))

(defn ->nsstring
  "Returns an autoreleased NSString for the Clojure string s."
  [s]
  (msg-send* :pointer
             (grease/get-objc-class "NSString")
             "stringWithUTF8String:" :pointer
             (dt-ffi/string->c s)))

;; =============================================================================
;; NSNumber
;; =============================================================================

(defn nsnumber->long
  "Returns the longLongValue of an NSNumber pointer as a Clojure long."
  [n]
  (msg-send* :int64 n "longLongValue"))

(defn nsnumber->double
  "Returns the doubleValue of an NSNumber pointer as a Clojure double."
  [n]
  (msg-send* :float64 n "doubleValue"))

(defn ->nsnumber-long
  "Returns an NSNumber wrapping the Clojure long v."
  [v]
  (msg-send* :pointer
             (grease/get-objc-class "NSNumber")
             "numberWithLongLong:" :int64 v))

(defn ->nsnumber-double
  "Returns an NSNumber wrapping the Clojure double v."
  [v]
  (msg-send* :pointer
             (grease/get-objc-class "NSNumber")
             "numberWithDouble:" :float64 v))

;; =============================================================================
;; NSArray
;; =============================================================================

(defn nsarray->vec
  "Converts an NSArray pointer to a Clojure vector of raw ObjC element pointers."
  [arr]
  (let [n (msg-send* :int64 arr "count")]
    (mapv #(msg-send* :pointer arr "objectAtIndex:" :int64 %) (range n))))

(defn ->nsarray
  "Returns an autoreleased NSMutableArray populated from coll.
  Each element of coll must be a raw ObjC object pointer."
  [coll]
  (let [a (msg-send* :pointer
                     (grease/get-objc-class "NSMutableArray")
                     "array")]
    (doseq [item coll]
      (msg-send* :void a "addObject:" :pointer item))
    a))

;; =============================================================================
;; NSDictionary
;; =============================================================================

(defn nsdict->map
  "Converts an NSDictionary to a Clojure map.
  Keys are converted to Clojure strings via [[nsstring->str]].
  Values are returned as raw ObjC pointers; use [[nsstring->str]],
  [[nsnumber->long]], etc. to convert them."
  [dict]
  (let [keys (nsarray->vec (msg-send* :pointer dict "allKeys"))]
    (into {}
          (map (fn [k]
                 [(nsstring->str k)
                  (msg-send* :pointer dict "objectForKey:" :pointer k)])
               keys))))

(defn nsdict->map-str
  "Converts an NSDictionary with NSString keys and NSString values to
  a Clojure map of strings."
  [dict]
  (into {}
        (map (fn [[k v]] [k (nsstring->str v)])
             (nsdict->map dict))))

(defn ->nsdict
  "Returns an NSMutableDictionary populated from the Clojure map m.
  Both keys and values of m must be raw ObjC object pointers."
  [m]
  (let [d (msg-send* :pointer
                     (grease/get-objc-class "NSMutableDictionary")
                     "dictionary")]
    (doseq [[k v] m]
      (msg-send* :void d "setObject:forKey:" :pointer v :pointer k))
    d))

(defn ->nsdict-str
  "Returns an NSMutableDictionary from a Clojure map of string → string."
  [m]
  (->nsdict (into {} (map (fn [[k v]] [(->nsstring k) (->nsstring v)]) m))))

;; =============================================================================
;; NSError
;; =============================================================================

(defn nserror->map
  "Converts an NSError pointer to a Clojure map with keys
  :domain (string), :code (long), and :description (string)."
  [err]
  {:domain      (nsstring->str (msg-send* :pointer err "domain"))
   :code        (msg-send* :int64 err "code")
   :description (nsstring->str (msg-send* :pointer err "localizedDescription"))})
