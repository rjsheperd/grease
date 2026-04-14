;; Objective-C FFI examples using objcjure.
;;
;; Usage: connect to the on-device nREPL (port 23456) and eval each top-level
;; form, or load the whole file with (load-file "path/to/objc.clj").
;;
;; The `objc` macro dispatches an Obj-C message send. Syntax:
;;   (objc [receiver :selector arg ...])
;;   (objc [receiver :sel1:sel2: arg1 arg2])

;; ObjC class names (NSMutableSet, UIApplication, …) are resolved at runtime
;; via the ObjC runtime and are not Clojure vars — suppress unresolved-symbol.
(ns ^{:clj-kondo/config {:linters {:unresolved-symbol {:level :off}}}} objc
  (:require [com.phronemophobic.objcjure :as objcjure :refer [objc]]
            [com.phronemophobic.grease :as grease]
            [com.phronemophobic.clj-libffi :as ffi]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; Basic class / instance usage
;; =============================================================================

;; Create and init an NSMutableSet
(def my-set
  (objc [[NSMutableSet :alloc] :init]))

(println (objcjure/objc->str my-set))

;; =============================================================================
;; Alert dialog
;; =============================================================================

(defn root-view-controller
  "Returns the current root UIViewController."
  []
  (objc [[[UIApplication :sharedApplication] :keyWindow] :rootViewController]))

(defn show-alert
  "Presents a UIAlertController dialog on the main thread."
  [title body ok-text]
  (objcjure/dispatch-main-async
   (fn []
     (let [alert  (objc [UIAlertController
                         :alertControllerWithTitle:message:preferredStyle
                         ~(objcjure/str->nsstring title)
                         ~(objcjure/str->nsstring body)
                         1])                         ; UIAlertControllerStyleAlert
           action (objc [UIAlertAction
                         :actionWithTitle:style:handler
                         ~(objcjure/str->nsstring ok-text)
                         0                           ; UIAlertActionStyleDefault
                         ~(objc (fn ^void [_action]))])]
       (objc [alert :addAction action])
       (objc [(root-view-controller)
              :presentViewController:animated:completion
              alert true nil])))))

(comment
  (show-alert "Hey!" "Check out Clojure on mobile!" "Okie dokie!"))

;; =============================================================================
;; msg-send — direct objc_msgSend for selectors with colons
;;
;; The `objc` macro requires keyword syntax (:selector) for message sends.
;; For method calls you want to construct dynamically, or when calling from
;; contexts where keyword lookup is ambiguous, use msg-send directly instead.
;;
;; NOTE: In the SCI nREPL, `objc` works with keyword selector syntax
;; (e.g. :setDelegate:) but NOT with trailing-colon symbol syntax
;; (e.g. setDelegate: — SCI's reader rejects symbols containing colons).
;; msg-send sidesteps this entirely.
;; =============================================================================

(defn msg-send
  "Send an ObjC message via objc_msgSend.

  ret-type   — return type keyword: :void :pointer :int32 :int64 etc.
  obj        — receiver (ObjC object pointer)
  sel-str    — selector string, e.g. \"setDelegate:\"
  typed-args — flat pairs of [type value], e.g. :pointer d :int32 0"
  [ret-type obj sel-str & typed-args]
  (let [sel (grease/register-objc-sel sel-str)]
    (apply ffi/call "objc_msgSend" ret-type
           :pointer obj :pointer sel
           typed-args)))

(comment
  ;; Equivalent to (objc [arr :addObject ns-obj])
  ;; where ns-obj is any ObjC pointer (e.g. from objc-rt/new-instance)
  (msg-send :void arr "addObject:" :pointer ns-obj))

;; =============================================================================
;; defclass — implement an ObjC class/delegate at runtime
;;
;; grease.ios.objc/defclass assembles a new ObjC class from Clojure fns.
;; It must be called at runtime (from the nREPL or app startup), not at
;; compile time.  The class pointer is def'd under the given symbol.
;;
;; Type encoding cheat-sheet:
;;   v = void   @ = id/object   : = SEL   q = long long   i = int
;;   "v@:"   → void method(id self, SEL _cmd)
;;   "v@:@"  → void method(id self, SEL _cmd, id arg1)
;;   "v@:@@" → void method(id self, SEL _cmd, id arg1, id arg2)
;;   "@@:"   → id   method(id self, SEL _cmd)
;; =============================================================================

;; Minimal delegate that just prints its selector argument.
(objc-rt/defclass PrintDelegate "NSObject"
  "locationManager:didUpdateLocations:" "v@:@@"
  (fn [_self _cmd _mgr locs]
    (println "[Clojure] locations update:" locs)))

(comment
  ;; Instantiate the class (holds a strong reference via the atom)
  (def my-delegate (atom (objc-rt/new-instance PrintDelegate))))

;; =============================================================================
;; CLLocationManager — full delegate example (proven working)
;;
;; Must retain both the manager and the delegate in atoms so ARC does not
;; release them when the setup fn returns.  Delegate wiring must happen on
;; the main thread.
;; =============================================================================

(def held-mgr      (atom nil))
(def held-delegate (atom nil))
(def last-location (atom nil))

(objc-rt/defclass LocationDelegate "NSObject"
  "locationManager:didUpdateLocations:" "v@:@@"
  (fn [_self _cmd _mgr locs]
    (println "[Clojure] locationManager:didUpdateLocations: locs=" locs)
    (reset! last-location locs)))

(defn start-location-updates!
  "Wire up a CLLocationManager on the main thread and begin location updates.
  Grant the 'Allow' permission dialog when it appears.
  After a few seconds @last-location will hold a CLLocation pointer."
  []
  (grease/dispatch-main-async
   (fn []
     (let [cls-mgr (grease/get-objc-class "CLLocationManager")
           mgr     (grease/objc-new cls-mgr)
           d       (objc-rt/new-instance LocationDelegate)]
       (reset! held-mgr mgr)
       (reset! held-delegate d)
       (msg-send :void mgr "setDelegate:" :pointer d)
       (msg-send :void mgr "requestWhenInUseAuthorization")
       (msg-send :void mgr "startUpdatingLocation")
       (println "[Clojure] CLLocationManager started")))))

(comment
  (start-location-updates!)

  ;; After granting permission on device:
  @last-location   ; => {:address 0x...} (non-nil)
  )

;; =============================================================================
;; NSArray sort using a comparator block
;; =============================================================================

(def arr (objc [NSMutableArray :array]))

(do
  (objc [arr :addObject ~(objcjure/str->nsstring "a")])
  (objc [arr :addObject ~(objcjure/str->nsstring "bb")])
  (objc [arr :addObject ~(objcjure/str->nsstring "ccc")])
  (objc [arr :addObject ~(objcjure/str->nsstring "dd")]))

(defn str-length [nsstr]
  (objc ^int [nsstr :length]))

;; Comparator block: sort descending by string length
(def length-comparator
  (objc (fn ^int [a b]
          (let [la (str-length a)
                lb (str-length b)]
            (cond (> la lb) -1
                  (= la lb)  0
                  :else      1)))))

(def sorted-array
  (objc [arr :sortedArrayUsingComparator length-comparator]))

(println (objcjure/objc->str sorted-array))
