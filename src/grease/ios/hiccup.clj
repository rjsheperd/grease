(ns grease.ios.hiccup
  "Hiccup-style UIKit view trees with a smart, incremental reconciler.

  [[render!]] / [[mount!]] mounts a hiccup vector as a UIKit view subtree.
  Subsequent renders reconcile in-place — patching only changed props, using
  keyed child matching to avoid unnecessary teardowns, and skipping unchanged
  subtrees entirely via reference-equality short-circuit.

  Five-layer diffing pipeline:
    Layer 1 — subtree identity skip (`identical?` / string `=` guard)
    Layer 2 — keyed child diffing (`insertSubview:atIndex:` reorder)
    Layer 3 — prop removal defaults + event-handler dedup
    Layer 4 — [[memo]] helper for user-controlled subtree caching
    Layer 5 — [[mount-subtree!]] for atom-scoped sub-region watches

  Hiccup format:
    `[:tag props? & children]`

  - `tag`      — keyword from `resources/grease/hiccup.edn` (e.g. `:label`)
  - `props`    — optional map of prop keywords to values
  - `children` — nested hiccup vecs or strings (auto-wrapped in `:label`)

  Example:
    (mount! :main-ui root-view [state]
      (fn []
        [:stack {:axis 1 :spacing 8.0}
         [:label {:text \"Hello\" :font [:system 18] :align 1}]
         [:button {:text \"OK\" :bg :system-blue
                   :on-tap (fn [] (println \"tapped\"))}]]))

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

(defn- wire-event!
  "Wires `callback-fn` to `control` for `event-int`, clearing any existing
  handlers first to prevent duplicate accumulation on re-render."
  [control event-int callback-fn]
  (let [addr (.address ^Object control)
        k    (gensym "event-")]
    ;; Clear all existing targets for this control before re-wiring so that
    ;; repeated renders never accumulate duplicate event handlers.
    (objc-rt/msg-send :void control
                      "removeTarget:action:forControlEvents:"
                      :pointer (f/null-ptr)
                      :pointer 0
                      :int64   event-int)
    (swap! event-dispatch dissoc addr)
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
;;
;; rendered-trees holds a richer entry per root-key:
;;   {root-key → {:node rendered-node
;;                :subtrees {subkey → {:node rendered-node :parent-view ptr}}}}

(def ^:private rendered-trees
  "Map of {root-key → {:node rendered-node :subtrees {subkey → {:node node :parent-view ptr}}}}
  for mounted subtrees."
  (atom {}))

(def ^:private mount-watches
  "Map of {root-key → [[atom watch-key] ...]} and
  {[root-key subkey] → [[atom watch-key] ...]} for reactive bindings."
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
  releases all resources in the subtree, including any mounted subtrees.

  No-op if `root-key` is not currently rendered."
  [root-key]
  (when-let [entry (get @rendered-trees root-key)]
    ;; entry may be a {:node ... :subtrees ...} map (new style) or a bare
    ;; rendered-node (injected by tests).
    (let [node     (if (contains? entry :node) (:node entry) entry)
          subtrees (when (contains? entry :subtrees) (:subtrees entry))]
      ;; Tear down subtrees first.
      (doseq [[_sk st] subtrees]
        (when-let [st-node (:node st)]
          (when (:view st-node)
            (objc-rt/msg-send :void (:view st-node) "removeFromSuperview"))
          (release-rendered! st-node)))
      ;; Tear down root node.
      (when node
        (when (:view node)
          (objc-rt/msg-send :void (:view node) "removeFromSuperview"))
        (release-rendered! node)))
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
;; render-tree! / reconcile! / reconcile-children! (Phase 4.2→6.3 + Layer 2)
;; =============================================================================

;; Forward declaration for mutual recursion between reconcile! and reconcile-children!.
(declare reconcile!)

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
      {:tag :label :props {:text hiccup-node} :key nil
       :view view :rk rk :children [] :hiccup hiccup-node})

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
      {:tag tag :props props :key k :view view :rk rk
       :children child-nodes :hiccup hiccup-node})

    :else
    (throw (ex-info "Unrecognised hiccup node" {:node hiccup-node}))))

(defn- new-hiccup-key
  "Returns the `:key` prop from a hiccup child form, or nil for strings."
  [child]
  (when (vector? child)
    (:key (second (normalize-hiccup child)))))

(defn- reconcile-children!
  "Reconciles `old-children` (rendered-nodes) with `new-children` (hiccup forms)
  under `parent-view`.

  If none of the old or new children have a non-nil `:key`, falls through to
  the positional algorithm.  Otherwise uses the keyed algorithm:

  1. Build `old-key-map` from old-children (keyed children only).
  2. Match new-children to old-children by key; unkeyed match positionally.
  3. Tear down leftover old children (keyed whose key is absent + extra unkeyed).
  4. Re-order reconciled children via `insertSubview:atIndex:` if needed.

  Returns a vector of reconciled rendered-nodes."
  [parent-view old-children new-children]
  (let [any-keyed? (or (some :key old-children)
                       (some new-hiccup-key new-children))]
    (if-not any-keyed?
      ;; ── Positional algorithm (original behaviour) ──────────────────────────
      (let [n-old  (count old-children)
            n-new  (count new-children)
            shared (min n-old n-new)
            reconciled (mapv #(reconcile! parent-view %1 %2)
                             (take shared old-children)
                             (take shared new-children))
            added      (mapv #(render-tree! parent-view %)
                             (drop shared new-children))]
        (doseq [extra (drop shared old-children)]
          (objc-rt/msg-send :void (:view extra) "removeFromSuperview")
          (release-rendered! extra))
        (into reconciled added))

      ;; ── Keyed algorithm ────────────────────────────────────────────────────
      (let [old-key-map   (into {} (keep (fn [n] (when (:key n) [(:key n) n]))
                                         old-children))
            old-unkeyed   (filterv #(nil? (:key %)) old-children)
            unkeyed-idx   (volatile! 0)
            new-nodes     (mapv (fn [new-child]
                                  (let [nk (new-hiccup-key new-child)]
                                    (cond
                                      ;; Keyed new child with matching old node.
                                      (and nk (contains? old-key-map nk))
                                      (reconcile! parent-view
                                                  (get old-key-map nk)
                                                  new-child)

                                      ;; Keyed new child with no old match — fresh insert.
                                      nk
                                      (render-tree! parent-view new-child)

                                      ;; Unkeyed — match positionally against old unkeyed.
                                      :else
                                      (let [idx @unkeyed-idx
                                            old (get old-unkeyed idx)]
                                        (vswap! unkeyed-idx inc)
                                        (if old
                                          (reconcile! parent-view old new-child)
                                          (render-tree! parent-view new-child))))))
                                new-children)
            ;; Keys present in old but not in new → tear down.
            new-keys      (set (keep new-hiccup-key new-children))
            leftover-keyed (remove (fn [n] (contains? new-keys (:key n)))
                                   (vals old-key-map))
            leftover-unkeyed (drop @unkeyed-idx old-unkeyed)]
        (doseq [extra (concat leftover-keyed leftover-unkeyed)]
          (objc-rt/msg-send :void (:view extra) "removeFromSuperview")
          (release-rendered! extra))
        ;; Re-order: emit insertSubview:atIndex: for any node not at target index.
        (let [current-subviews (f/nsarray->vec
                                (objc-rt/msg-send :pointer parent-view "subviews"))
              view->idx        (into {} (map-indexed (fn [i v] [v i]) current-subviews))]
          (doseq [[target-idx node] (map-indexed vector new-nodes)]
            (let [current-idx (get view->idx (:view node))]
              (when (and current-idx (not= current-idx target-idx))
                (objc-rt/msg-send :void parent-view
                                  "insertSubview:atIndex:"
                                  :pointer (:view node)
                                  :int64   (long target-idx)))))
          new-nodes)))))

(defn- reconcile!
  "Reconciles `old-node` with `new-hiccup` under `parent-view`.
  - Same reference (or same string): returns old-node immediately — zero ObjC calls.
  - Same tag: patches only changed props and reconciles children in-place.
  - Different tag: removes old subtree, builds a fresh one.
  Returns the new rendered-node.

  `:key` and `:constraints` are stripped from the stored `:props`; constraints
  are not re-applied during reconciliation (they are static at mount time)."
  [parent-view old-node new-hiccup]
  ;; Layer 1 — subtree identity skip.
  ;; For vectors: identical? reference means nothing could have changed.
  ;; For strings: value equality is sufficient (strings are immutable).
  (if (if (string? new-hiccup)
        (= new-hiccup (:hiccup old-node))
        (identical? new-hiccup (:hiccup old-node)))
    old-node
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
              old-kids (:children old-node)]
          ;; First pass: apply changed props from new-props.
          (doseq [[k v] new-props]
            (when (not= v (get (:props old-node) k))
              (apply-prop! view k v)))
          ;; Second pass: reset props that were present in old but absent in new.
          (doseq [k (keys (:props old-node))]
            (when-not (contains? new-props k)
              (let [spec (prop-spec k)]
                (when (and spec
                           (not (:no-reconcile spec))
                           (not= :identity (:special spec)))
                  (apply-prop! view k (or (:default spec) (f/null-ptr)))))))
          (let [new-kids (reconcile-children! view old-kids new-children)]
            {:tag new-tag :props new-props :key new-key :view view
             :rk (:rk old-node) :children new-kids}))))))

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
  (if-let [entry (get @rendered-trees root-key)]
    (let [old-node (if (contains? entry :node) (:node entry) entry)
          new-node (reconcile! root-view old-node hiccup-tree)]
      (swap! rendered-trees update root-key
             (fn [e] (if (contains? e :node)
                       (assoc e :node new-node)
                       new-node))))
    (do
      (let [subs (f/nsarray->vec (objc-rt/msg-send :pointer root-view "subviews"))]
        (doseq [sv subs]
          (objc-rt/msg-send :void sv "removeFromSuperview")))
      (let [node (render-tree! root-view hiccup-tree)]
        (swap! rendered-trees assoc root-key {:node node :subtrees {}}))))
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
  "Removes all reactive watches for `root-key` and tears down the rendered subtree,
  including all subtrees mounted via [[mount-subtree!]].

  Safe to call if the key is not currently mounted."
  [root-key]
  ;; Remove subtree watches.
  (when-let [entry (get @rendered-trees root-key)]
    (let [subtrees (when (contains? entry :subtrees) (:subtrees entry))]
      (doseq [sk (keys subtrees)]
        (let [compound [root-key sk]]
          (when-let [watch-pairs (get @mount-watches compound)]
            (doseq [[a wk] watch-pairs]
              (remove-watch a wk))
            (swap! mount-watches dissoc compound))))))
  ;; Remove root watches.
  (when-let [watch-pairs (get @mount-watches root-key)]
    (doseq [[a wk] watch-pairs]
      (remove-watch a wk))
    (swap! mount-watches dissoc root-key))
  (teardown-rendered! root-key))

(defn memo
  "Returns a zero-arg render function that caches its last result.

  Re-evaluates `body-fn` and returns a new hiccup tree only when
  `(= (deps-fn) last-deps)` is false.  When deps have not changed,
  returns the identical cached reference — enabling [[reconcile!]] to
  skip the subtree entirely via the Layer 1 identity check.

  - `deps-fn` — zero-arg fn returning a vector of comparable dep values
  - `body-fn` — zero-arg fn returning a hiccup tree"
  [deps-fn body-fn]
  (let [cache (atom {:deps ::unset :hiccup nil})]
    (fn []
      (let [new-deps              (deps-fn)
            {:keys [deps hiccup]} @cache]
        (if (= deps new-deps)
          hiccup
          (let [new-hiccup (body-fn)]
            (reset! cache {:deps new-deps :hiccup new-hiccup})
            new-hiccup))))))

(defn mount-subtree!
  "Like [[mount!]] but scoped to a sub-region of an existing mount.

  Only atoms in `atoms` trigger re-render of this subtree; changes to other
  atoms watched by the parent mount do not affect it.

  - `root-key`    — the parent mount's root-key (must already exist in rendered-trees)
  - `subkey`      — unique keyword identifying this subtree within the parent
  - `parent-view` — UIView to render the subtree into
  - `atoms`       — sequence of atoms that drive this subtree
  - `render-fn`   — `(fn [] hiccup-tree)`

  Returns `parent-view`."
  [root-key subkey parent-view atoms render-fn]
  (let [compound  [root-key subkey]
        pending?  (volatile! false)
        watch-fn  (fn [_ _ _ _]
                    (when-not @pending?
                      (vreset! pending? true)
                      (grease/dispatch-main-async
                       (fn []
                         (vreset! pending? false)
                         (let [entry    (get @rendered-trees root-key)
                               st-entry (get-in entry [:subtrees subkey])
                               old-node (:node st-entry)
                               new-node (if old-node
                                          (reconcile! parent-view old-node (render-fn))
                                          (render-tree! parent-view (render-fn)))]
                           (swap! rendered-trees update root-key
                                  assoc-in [:subtrees subkey :node] new-node))))))
        watch-pairs (mapv (fn [a]
                            (let [wk (gensym "subtree-")]
                              (add-watch a wk watch-fn)
                              [a wk]))
                          atoms)]
    (swap! mount-watches assoc compound watch-pairs)
    ;; Ensure root entry exists with subtrees map.
    (swap! rendered-trees update root-key
           (fn [e]
             (if (and e (contains? e :subtrees))
               e
               {:node e :subtrees {}})))
    (swap! rendered-trees assoc-in [root-key :subtrees subkey]
           {:node nil :parent-view parent-view})
    (grease/dispatch-main-async
     #(let [new-node (render-tree! parent-view (render-fn))]
        (swap! rendered-trees assoc-in [root-key :subtrees subkey]
               {:node new-node :parent-view parent-view})))
    parent-view))

(defn unmount-subtree!
  "Removes watches and tears down the rendered subtree for `[root-key subkey]`.

  Safe to call if the subtree is not mounted."
  [root-key subkey]
  (let [compound [root-key subkey]]
    (when-let [watch-pairs (get @mount-watches compound)]
      (doseq [[a wk] watch-pairs]
        (remove-watch a wk))
      (swap! mount-watches dissoc compound))
    (when-let [st-entry (get-in @rendered-trees [root-key :subtrees subkey])]
      (when-let [st-node (:node st-entry)]
        (when (:view st-node)
          (objc-rt/msg-send :void (:view st-node) "removeFromSuperview"))
        (release-rendered! st-node))
      (swap! rendered-trees update root-key update :subtrees dissoc subkey))))
