(ns grease.ios.notify
  "NSNotificationCenter as a Clojure event bus.

  [[on]] registers a callback for a notification key and returns a
  subscription handle.  [[off]] unregisters it.  [[once]] auto-removes
  after the first invocation.

  Notification keys are looked up in `resources/grease/notifications.edn`.
  Raw ObjC notification name strings are also accepted for notifications
  not in the registry.

  The callback receives a map:
    {:name   \"UIKeyboardWillShowNotification\"   ; ObjC name string
     :notification <NSNotification pointer>}

  The ObjC observer is registered with `NSNotificationCenter` only while at
  least one subscriber exists for a given notification name, and removed
  automatically when the last subscriber calls [[off]]."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; Notification name registry
;; =============================================================================

(def ^:private notification-names
  "Map of keyword → {:objc \"UINotificationNameString\"}, loaded from EDN."
  (delay
    (edn/read-string (slurp (io/resource "grease/notifications.edn")))))

(defn- resolve-objc-name
  "Returns the ObjC notification name string for `k`.
  `k` may be a keyword (looked up in the registry) or a plain string."
  [k]
  (if (string? k)
    k
    (let [entry (get @notification-names k)]
      (when-not entry
        (throw (ex-info (str "Unknown notification key: " k)
                        {:key k :known (keys @notification-names)})))
      (:objc entry))))

;; =============================================================================
;; Dispatch table
;; =============================================================================

;; {objc-name-string → {handle-id → callback-fn}}
(def ^:no-doc dispatch-table (atom {}))

;; ObjC notification name strings currently registered in NSNotificationCenter
(def ^:private registered-names (atom #{}))

;; =============================================================================
;; GreaseNotificationObserver ObjC class (registered once at ns load)
;; =============================================================================

(defonce ^:no-doc _notification-observer-cls
  (let [cls (grease/allocate-objc-class!
             "GreaseNotificationObserver"
             (grease/get-objc-class "NSObject"))
        imp (grease/make-imp
             (fn [_self _cmd notification]
               (let [name-ptr  (objc-rt/msg-send :pointer notification "name")
                     name-str  (f/nsstring->str name-ptr)
                     callbacks (vals (get @dispatch-table name-str))]
                 (doseq [cb callbacks]
                   (try
                     (cb {:name name-str :notification notification})
                     (catch Exception _)))))
             [:pointer]
             :void)
        sel (grease/register-objc-sel "handleNotification:")]
    (grease/add-objc-method! cls sel imp "v@:@")
    (grease/register-objc-class! cls)
    cls))

(defonce ^:no-doc _notification-observer
  (grease/objc-new _notification-observer-cls))

;; =============================================================================
;; NSNotificationCenter wiring helpers
;; =============================================================================

(defn- notification-center []
  (objc-rt/msg-send :pointer
                    (grease/get-objc-class "NSNotificationCenter")
                    "defaultCenter"))

(defn- wire-observer!
  "Registers the shared observer for `objc-name` in NSNotificationCenter."
  [objc-name]
  (objc-rt/msg-send :void
                    (notification-center)
                    "addObserver:selector:name:object:"
                    :pointer _notification-observer
                    :pointer (grease/register-objc-sel "handleNotification:")
                    :pointer (f/->nsstring objc-name)
                    :pointer (f/null-ptr))
  (swap! registered-names conj objc-name))

(defn- unwire-observer!
  "Removes the shared observer for `objc-name` from NSNotificationCenter."
  [objc-name]
  (objc-rt/msg-send :void
                    (notification-center)
                    "removeObserver:name:object:"
                    :pointer _notification-observer
                    :pointer (f/->nsstring objc-name)
                    :pointer (f/null-ptr))
  (swap! registered-names disj objc-name))

;; =============================================================================
;; Public API (Phase 3.3 / 3.4)
;; =============================================================================

(defn on
  "Registers `callback-fn` to be called when `notification-key` fires.

  `notification-key` may be a keyword from `resources/grease/notifications.edn`
  (e.g. `:keyboard-will-show`) or a raw ObjC notification name string.

  The callback receives:
    `{:name \"UINotificationName...\" :notification <NSNotification pointer>}`

  Returns a subscription handle; pass it to [[off]] to unsubscribe."
  [notification-key callback-fn]
  (let [objc-name (resolve-objc-name notification-key)
        handle-id (gensym "notif-handle-")]
    (swap! dispatch-table update objc-name assoc handle-id callback-fn)
    (when-not (contains? @registered-names objc-name)
      (wire-observer! objc-name))
    {:notification-key notification-key
     :objc-name        objc-name
     :handle-id        handle-id}))

(defn off
  "Unregisters the subscription associated with `handle`.
  Removes the ObjC observer when the last subscriber for a notification exits."
  [handle]
  (let [{:keys [objc-name handle-id]} handle]
    (swap! dispatch-table update objc-name dissoc handle-id)
    (when (empty? (get @dispatch-table objc-name))
      (swap! dispatch-table dissoc objc-name)
      (unwire-observer! objc-name))))

(defn once
  "Registers `callback-fn` for `notification-key`; auto-removes after first fire.
  Returns the subscription handle."
  [notification-key callback-fn]
  (let [handle-ref (atom nil)
        wrapped    (fn [event]
                     (callback-fn event)
                     (when-let [h @handle-ref]
                       (off h)))]
    (reset! handle-ref (on notification-key wrapped))
    @handle-ref))
