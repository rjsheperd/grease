(ns grease.ios.hiccup
  "Hiccup-style UIKit view trees.

  [[render!]] mounts a hiccup vector as a UIKit view subtree under a named
  root view.  Subsequent calls to [[render!]] with the same key tear down the
  old subtree and rebuild from scratch (naive reconciler).

  Hiccup format:
    `[:tag props? & children]`

  - `tag`      — keyword from `resources/grease/hiccup.edn` (e.g. `:label`)
  - `props`    — optional map of prop keywords to values
  - `children` — nested hiccup vecs or strings (auto-wrapped in `:label`)

  Example:
    (render! :main-ui
      [:stack {:axis 1 :spacing 8.0}
       [:label {:text \"Hello\" :font [:system 18] :align 1}]
       [:button {:text \"OK\" :bg :system-blue
                 :on-tap (fn [] (println \"tapped\"))}]])

  All UIKit operations are dispatched to the main thread via
  `grease/dispatch-main-async`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.phronemophobic.grease :as grease]
            [grease.ios.color :as color]
            [grease.ios.font :as font]
            [grease.ios.foundation :as f]
            [grease.ios.invoke :as invoke]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private registry
  "Hiccup tag + prop registry loaded from resources/grease/hiccup.edn."
  (delay
    (edn/read-string (slurp (io/resource "grease/hiccup.edn")))))

(defn- tag-spec [tag]
  (or (get-in @registry [:tags tag])
      (throw (ex-info (str "Unknown hiccup tag: " tag)
                      {:tag tag :known (keys (:tags @registry))}))))

(defn- prop-spec [prop]
  (get-in @registry [:props prop]))

;; =============================================================================
;; Coercion
;; =============================================================================

(defn- coerce-value [coerce-kw v]
  (case coerce-kw
    :ui-color  (color/->uicolor v)
    :ns-string (f/->nsstring v)
    :ui-font   (font/->uifont v)
    ;; :cg-color is a CGColorRef; obtain from UIColor
    :cg-color  (objc-rt/msg-send :pointer (color/->uicolor v) "CGColor")
    v))

;; =============================================================================
;; Event handler dispatch table
;; =============================================================================

;; {control-ptr-address → {control-event-int → callback-fn}}
(def ^:no-doc event-dispatch (atom {}))

(defonce ^:no-doc _control-target-cls
  (let [cls (grease/allocate-objc-class!
             "GreaseControlTarget"
             (grease/get-objc-class "NSObject"))
        imp (grease/make-imp
             (fn [_self _cmd sender]
               (when-let [handlers (get @event-dispatch
                                        (.address ^tech.v3.datatype.ffi.Pointer sender))]
                 (doseq [[_ cb] handlers]
                   (try (cb) (catch Exception _)))))
             [:pointer]
             :void)
        sel (grease/register-objc-sel "handleEvent:")]
    (grease/add-objc-method! cls sel imp "v@:@")
    (grease/register-objc-class! cls)
    cls))

(defonce ^:no-doc _control-target-instance
  (grease/objc-new _control-target-cls))

(defn- wire-event! [control event-int callback-fn]
  (let [addr (.address ^tech.v3.datatype.ffi.Pointer control)
        k    (gensym "event-")]
    (swap! event-dispatch update addr assoc k callback-fn)
    (objc-rt/msg-send :void control
                      "addTarget:action:forControlEvents:"
                      :pointer _control-target-instance
                      :pointer (grease/register-objc-sel "handleEvent:")
                      :int64   event-int)))

;; =============================================================================
;; set-frame! (needs the CGRect dispatch path)
;; =============================================================================

(def ^:private set-frame-spec
  {:selector "setFrame:"
   :encoding "v@:{CGRect={CGPoint=dd}{CGSize=dd}}"
   :args     [{:name "frame" :type "CGRect"}]
   :return   "void"})

(defn- apply-frame! [view [x y w h]]
  (invoke/dispatch!
   view "setFrame:" set-frame-spec
   [{:origin {:x (double x) :y (double y)}
     :size   {:width (double w) :height (double h)}}]))

;; =============================================================================
;; Prop application
;; =============================================================================

(defn- apply-prop! [view prop v]
  (let [spec (prop-spec prop)]
    (when-not spec
      (throw (ex-info (str "Unknown hiccup prop: " prop)
                      {:prop prop :known (keys (:props @registry))})))
    (cond
      ;; Special: frame
      (:special spec)
      (case (:special spec)
        :set-frame (apply-frame! view v))

      ;; Control event handler
      (:control-event spec)
      (wire-event! view (:control-event spec) v)

      ;; Layer target (e.g. setCornerRadius:)
      (= :layer (:target spec))
      (let [layer  (objc-rt/msg-send :pointer view "layer")
            coerce (:coerce spec)
            val    (if coerce (coerce-value coerce v) v)]
        (case (or (:arg-type spec) :pointer)
          :float64 (objc-rt/msg-send :void layer (:sel spec) :float64 (double val))
          :int64   (objc-rt/msg-send :void layer (:sel spec) :int64 (long val))
          :pointer (objc-rt/msg-send :void layer (:sel spec) :pointer val)))

      ;; Standard selector
      :else
      (let [coerce (:coerce spec)
            val    (if coerce (coerce-value coerce v) v)]
        (case (or (:arg-type spec) :pointer)
          :float64 (objc-rt/msg-send :void view (:sel spec) :float64 (double val))
          :int64   (objc-rt/msg-send :void view (:sel spec) :int64 (long val))
          :pointer (objc-rt/msg-send :void view (:sel spec) :pointer val))))))

;; =============================================================================
;; Element creation (Phase 4.1)
;; =============================================================================

(defn create-element
  "Creates a UIKit view for `tag` with `props` applied.

  `tag` must be a keyword registered in `resources/grease/hiccup.edn`.
  `props` is an optional map of prop keywords to values.

  Returns the view pointer.  The view is retained in the registry under a
  generated key."
  ([tag] (create-element tag {}))
  ([tag props]
   (let [spec  (tag-spec tag)
         view  (if (:factory spec)
                 (objc-rt/msg-send :pointer
                                   (grease/get-objc-class (:class spec))
                                   (:factory spec)
                                   :int64 (long (:factory-arg spec 0)))
                 (objc-rt/msg-send :pointer
                                   (grease/get-objc-class (:class spec))
                                   "new"))
         rk    (retain/retain! (gensym "hiccup-") view ::hiccup-view)]
     (doseq [[prop v] props]
       (apply-prop! view prop v))
     (vary-meta view assoc ::retain-key rk))))

;; =============================================================================
;; Tree rendering (Phase 4.2 — naive reconciler)
;; =============================================================================

(def ^:private render-roots
  "Map of {root-key → [retain-keys...]} for mounted subtrees."
  (atom {}))

(defn- normalize-hiccup
  "Returns [tag props children] from a hiccup vector."
  [[tag & rest]]
  (if (map? (first rest))
    [tag (first rest) (next rest)]
    [tag {} rest]))

(defn- render-node!
  "Recursively renders a hiccup node under `parent`.
  Returns the created view pointer."
  [parent node]
  (cond
    ;; String → label
    (string? node)
    (let [view (create-element :label {:text node})]
      (objc-rt/msg-send :void parent "addSubview:" :pointer view)
      view)

    ;; Hiccup vector
    (vector? node)
    (let [[tag props children] (normalize-hiccup node)
          view (create-element tag props)]
      (objc-rt/msg-send :void parent "addSubview:" :pointer view)
      (doseq [child children]
        (render-node! view child))
      view)

    :else
    (throw (ex-info "Unrecognised hiccup node" {:node node}))))

(defn- teardown! [root-key]
  (when-let [rks (get @render-roots root-key)]
    (doseq [rk rks]
      (retain/release! rk)))
  (swap! render-roots dissoc root-key))

(defn render!
  "Renders `hiccup-tree` as a UIKit subtree under `root-view`.

  The first call populates `root-view` with the rendered tree and associates
  it with `root-key`.  Subsequent calls with the same key tear down the
  previous subtree (removing subviews) and render fresh.

  All UIKit operations run synchronously on the calling thread.  Wrap in
  `grease/dispatch-main-async` when calling from a background thread.

  Returns `root-view`."
  [root-key root-view hiccup-tree]
  (teardown! root-key)
  ;; Remove all existing subviews
  (let [subs (f/nsarray->vec (objc-rt/msg-send :pointer root-view "subviews"))]
    (doseq [sv subs]
      (objc-rt/msg-send :void sv "removeFromSuperview")))
  ;; Render fresh
  (let [before-count (retain/retained-count)]
    (render-node! root-view hiccup-tree)
    (let [new-rks (drop before-count (retain/retained-keys))]
      (swap! render-roots assoc root-key (vec new-rks))))
  root-view)
