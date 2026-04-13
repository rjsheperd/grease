;; Objective-C FFI examples using objcjure.
;;
;; Usage: connect to the on-device nREPL (port 23456) and eval each top-level
;; form, or load the whole file with (load-file "path/to/objc.clj").
;;
;; The `objc` macro dispatches an Obj-C message send. Syntax:
;;   (objc [receiver :selector arg ...])
;;   (objc [receiver :sel1:sel2: arg1 arg2])

(ns objc
  (:require [com.phronemophobic.objcjure :as objcjure :refer [objc]]))

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
  (show-alert "Hey!" "Check out Clojure on mobile!" "Okie dokie!")
  ,)

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
