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

  Null pointer caution: clj-libffi rejects `java.lang.Long` AND Clojure `nil`
  for `:pointer` arguments (`PToPointer` protocol not implemented for either).
  Use [[null-ptr]] for nil ObjC pointer args, and [[main-queue]] for a nil
  dispatch_queue_t (which tells CB/CL managers to use the main queue)."
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [grease.ios.retain :as retain]
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
;; NSURL
;; =============================================================================

(defn string->nsurl
  "Returns an NSURL for the Clojure string url-string."
  [url-string]
  (msg-send* :pointer
             (grease/get-objc-class "NSURL")
             "URLWithString:" :pointer
             (->nsstring url-string)))

(defn nsurl->string
  "Returns the absolute string of an NSURL pointer as a Clojure string."
  [url]
  (nsstring->str (msg-send* :pointer url "absoluteString")))

;; =============================================================================
;; Null pointer helpers
;; =============================================================================

(defn null-ptr
  "Returns a native null pointer usable with clj-libffi :pointer args.
  Use wherever an ObjC API expects a nil/NULL pointer argument.

  Example:
    ;; Pass nil options to CBCentralManager
    (msg-send :pointer mgr \"initWithDelegate:queue:options:\"
              :pointer d :pointer (f/main-queue) :pointer (f/null-ptr))"
  []
  (ffi/call "grease_null_ptr" :pointer))

(defn main-queue
  "Returns the main GCD dispatch_queue_t pointer.
  Pass as :pointer to any ObjC API that accepts a dispatch_queue_t.
  Equivalent to passing nil (which means 'use main queue') but compatible
  with clj-libffi's PToPointer protocol."
  []
  (ffi/call "grease_main_queue" :pointer))

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

;; =============================================================================
;; Resource-management macros
;; =============================================================================

(defmacro with-autorelease
  "Execute `body` inside a fresh ObjC autorelease pool.

  Any autoreleased ObjC objects created during `body` are drained when
  the form exits (normally or via exception). Use this around tight loops
  that create many temporary NSStrings, NSDates, etc. to avoid unbounded
  memory growth.

  Example:
    (with-autorelease
      (dotimes [i 10000]
        (->nsstring (str \"item-\" i))))"
  [& body]
  `(let [pool# (ffi/call "objc_autoreleasePoolPush" :pointer)]
     (try
       ~@body
       (finally
         (ffi/call "objc_autoreleasePoolPop" :void :pointer pool#)))))

(defmacro with-retained
  "Retain ObjC objects for the duration of `body`, releasing them on exit.

  `bindings` follows the same syntax as `let`: alternating symbols and
  init-exprs. Each value is registered in [[grease.ios.retain]] under a
  generated key. All entries are released in a `finally` block regardless
  of whether `body` throws.

  Callers should send any required teardown messages (e.g.
  `removeObserver:`, `stopUpdatingLocation`) inside the body before the
  macro releases the pointers.

  Example:
    (with-retained [mgr  (make-location-manager)
                    dlg  (make-delegate)]
      (wire-and-start! mgr dlg)
      @result-promise)"
  [bindings & body]
  (assert (even? (count bindings))
          "with-retained bindings must have an even number of forms")
  (let [pairs   (partition 2 bindings)
        syms    (map first pairs)
        inits   (map second pairs)
        ks      (map (fn [_] (gensym "retain-key-")) syms)]
    `(let ~(vec (interleave syms inits))
       (let [keys# ~(mapv (fn [k s] `(retain/retain! '~k ~s ::with-retained))
                          ks syms)]
         (try
           ~@body
           (finally
             (doseq [k# keys#]
               (retain/release! k#))))))))
