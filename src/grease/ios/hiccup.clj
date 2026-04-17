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
            [com.phronemophobic.grease :as grease]
            [grease.ios-host :as host]
            [grease.ios.color :as color]
            [grease.ios.font :as font]
            [grease.ios.foundation :as f]
            [grease.ios.invoke :as invoke]
            [grease.ios.layout :as layout]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private registry
  "Hiccup tag + prop registry loaded from resources/grease/hiccup.edn."
  (delay
    (edn/read-string (host/read-resource "grease/hiccup.edn"))))

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
                                        (.address ^Object sender))]
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
  (let [addr (.address ^Object control)
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
      ;; Special: frame or tree-level no-ops (:key, :constraints)
      (:special spec)
      (case (:special spec)
        :set-frame (apply-frame! view v)
        :identity  nil)

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
;; Tree rendering (Phase 4.3 — smart reconciler + reactive bindings)
;; =============================================================================

;; A rendered-node is a plain map:
;;   {:tag kw :props map :view ptr :rk retain-key :children [rendered-node...]}

(def ^:private rendered-trees
  "Map of {root-key → rendered-node} for mounted subtrees."
  (atom {}))

(def ^:private mount-watches
  "Map of {root-key → [[atom watch-key] ...]} for reactive `mount!` bindings."
  (atom {}))

(defn- normalize-hiccup
  "Returns [tag props children] from a hiccup vector."
  [[tag & rest]]
  (if (map? (first rest))
    [tag (first rest) (next rest)]
    [tag {} rest]))

(defn- release-rendered!
  "Recursively releases all retain keys in the rendered subtree.
  Does NOT call `removeFromSuperview` — callers must handle that."
  [node]
  (when node
    (doseq [child (:children node)]
      (release-rendered! child))
    (retain/release! (:rk node))))

(defn teardown-rendered!
  "Removes the top-level rendered view for `root-key` from its superview and
  releases all resources in the subtree.

  No-op if `root-key` is not currently rendered."
  [root-key]
  (when-let [node (get @rendered-trees root-key)]
    (objc-rt/msg-send :void (:view node) "removeFromSuperview")
    (release-rendered! node)
    (swap! rendered-trees dissoc root-key)))

;; =============================================================================
;; Constraint integration (Phase 6.3)
;; =============================================================================

(defn- constraint-key->view
  "Builds the symbolic-key → view-pointer map for constraint resolution.
  `:parent` maps to the container view; child `:key` props map to their views."
  [container-view child-nodes]
  (into {:parent container-view}
        (keep (fn [cn] (when (:key cn) [(:key cn) (:view cn)]))
              child-nodes)))

(defn- parse-constraint-tuple
  "Converts a hiccup constraint tuple to a [[grease.ios.layout/constraint]] spec map.

  Format: `[relation item1-ref attr1 item2-ref attr2 constant? multiplier?]`

  `key->view` maps symbolic keys to live view pointers:
  - `:parent` — the container view
  - Any keyword set as a child's `:key` prop

  Both `item1-ref` and `item2-ref` must resolve in `key->view`; nil refs are
  not supported (fixed-dimension constraints require passing the same view for
  both items).

  Throws with `:key` and `:available` info when a ref cannot be resolved."
  [key->view [rel ref1 attr1 ref2 attr2 & rest]]
  (let [item1 (get key->view ref1)
        _     (when-not item1
                (throw (ex-info (str "Constraint item1-ref not found: " ref1)
                                {:key ref1 :available (keys key->view)})))
        item2 (get key->view ref2)
        _     (when-not item2
                (throw (ex-info (str "Constraint item2-ref not found: " ref2)
                                {:key ref2 :available (keys key->view)})))]
    {:item1      item1
     :attr1      (or attr1 :not-an-attr)
     :relation   rel
     :item2      item2
     :attr2      (or attr2 :not-an-attr)
     :multiplier (double (or (second rest) 1.0))
     :constant   (double (or (first rest) 0.0))}))

(defn- apply-constraints!
  "Resolves and activates `constraints` (a vector of constraint tuples) on
  `container-view` using `child-nodes` for symbolic key resolution."
  [container-view child-nodes constraints]
  (let [kv    (constraint-key->view container-view child-nodes)
        specs (mapv #(parse-constraint-tuple kv %) constraints)]
    (layout/constrain! container-view specs)))

;; =============================================================================
;; render-tree! (Phase 4.2→6.3)
;; =============================================================================

(defn- render-tree!
  "Creates a UIKit view tree from `hiccup-node` and adds it as a subview of
  `parent-view`.  Returns a rendered-node map.

  Rendered-node shape: `{:tag kw :props map :key kw-or-nil :view ptr :rk rk :children [node...]}`

  When `:constraints` is present on a vector node, activates the Auto Layout
  constraints on the container after all children are created.  `:key` and
  `:constraints` are stripped from the stored `:props` map."
  [parent-view hiccup-node]
  (cond
    (string? hiccup-node)
    (let [view (create-element :label {:text hiccup-node})
          rk   (-> view meta ::retain-key)]
      (objc-rt/msg-send :void parent-view "addSubview:" :pointer view)
      {:tag :label :props {:text hiccup-node} :key nil :view view :rk rk :children []})

    (vector? hiccup-node)
    (let [[tag all-props children] (normalize-hiccup hiccup-node)
          k           (:key all-props)
          constraints (:constraints all-props)
          props       (dissoc all-props :key :constraints)
          view        (create-element tag props)
          rk          (-> view meta ::retain-key)
          _           (objc-rt/msg-send :void parent-view "addSubview:" :pointer view)
          child-nodes (mapv #(render-tree! view %) children)]
      (when constraints
        (apply-constraints! view child-nodes constraints))
      {:tag tag :props props :key k :view view :rk rk :children child-nodes})

    :else
    (throw (ex-info "Unrecognised hiccup node" {:node hiccup-node}))))

(defn- reconcile!
  "Reconciles `old-node` with `new-hiccup` under `parent-view`.
  - Same tag: patches only changed props and reconciles children in-place.
  - Different tag: removes old subtree, builds a fresh one.
  Returns the new rendered-node.

  `:key` and `:constraints` are stripped from the stored `:props`; constraints
  are not re-applied during reconciliation (they are static at mount time)."
  [parent-view old-node new-hiccup]
  (let [[new-tag all-new-props new-children]
        (if (string? new-hiccup)
          [:label {:text new-hiccup} []]
          (normalize-hiccup new-hiccup))
        new-key   (:key all-new-props)
        new-props (dissoc all-new-props :key :constraints)]
    (if (not= (:tag old-node) new-tag)
      (do
        (objc-rt/msg-send :void (:view old-node) "removeFromSuperview")
        (release-rendered! old-node)
        (render-tree! parent-view new-hiccup))
      (let [view     (:view old-node)
            old-kids (:children old-node)
            n-old    (count old-kids)
            n-new    (count new-children)
            shared   (min n-old n-new)]
        (doseq [[k v] new-props]
          (when (not= v (get (:props old-node) k))
            (apply-prop! view k v)))
        (let [reconciled (mapv #(reconcile! view %1 %2)
                               (take shared old-kids)
                               (take shared new-children))
              added      (mapv #(render-tree! view %)
                               (drop shared new-children))]
          (doseq [extra (drop shared old-kids)]
            (objc-rt/msg-send :void (:view extra) "removeFromSuperview")
            (release-rendered! extra))
          {:tag new-tag :props new-props :key new-key :view view
           :rk (:rk old-node) :children (into reconciled added)})))))

(defn render!
  "Renders `hiccup-tree` as a UIKit subtree under `root-view`.

  On the first render for `root-key`, clears all existing subviews of
  `root-view` and builds the tree from scratch.  On subsequent renders,
  performs an incremental reconciliation — patching changed props and
  adding/removing children — without tearing down unchanged branches.

  All UIKit operations run synchronously on the calling thread.  Wrap in
  `grease/dispatch-main-async` when calling from a background thread.

  Returns `root-view`."
  [root-key root-view hiccup-tree]
  (if-let [old-node (get @rendered-trees root-key)]
    (let [new-node (reconcile! root-view old-node hiccup-tree)]
      (swap! rendered-trees assoc root-key new-node))
    (do
      (let [subs (f/nsarray->vec (objc-rt/msg-send :pointer root-view "subviews"))]
        (doseq [sv subs]
          (objc-rt/msg-send :void sv "removeFromSuperview")))
      (let [node (render-tree! root-view hiccup-tree)]
        (swap! rendered-trees assoc root-key node))))
  root-view)

(defn mount!
  "Reactively mounts a hiccup view under `root-view`, re-rendering automatically
  whenever any atom in `atoms` changes.

  - `root-key`  — unique keyword or symbol identifying this mount point
  - `root-view` — UIView to render into
  - `atoms`     — sequence of `clojure.lang.Atom` instances to watch
  - `render-fn` — `(fn [] hiccup-tree)` called with no args on each render pass

  A `volatile!` pending flag coalesces rapid atom changes into a single
  main-thread render pass.

  Returns `root-view`."
  [root-key root-view atoms render-fn]
  (let [pending?  (volatile! false)
        watch-fn  (fn [_ _ _ _]
                    (when-not @pending?
                      (vreset! pending? true)
                      (grease/dispatch-main-async
                       (fn []
                         (vreset! pending? false)
                         (render! root-key root-view (render-fn))))))
        watch-pairs (mapv (fn [a]
                            (let [wk (gensym "mount-")]
                              (add-watch a wk watch-fn)
                              [a wk]))
                          atoms)]
    (swap! mount-watches assoc root-key watch-pairs)
    (grease/dispatch-main-async
     #(render! root-key root-view (render-fn)))
    root-view))

(defn unmount!
  "Removes all reactive watches for `root-key` and tears down the rendered subtree.

  Safe to call if the key is not currently mounted."
  [root-key]
  (when-let [watch-pairs (get @mount-watches root-key)]
    (doseq [[a wk] watch-pairs]
      (remove-watch a wk))
    (swap! mount-watches dissoc root-key))
  (teardown-rendered! root-key))
