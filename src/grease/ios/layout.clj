(ns grease.ios.layout
  "NSLayoutConstraint as Clojure data.

  [[constrain!]] activates a sequence of NSLayoutConstraint specs on a parent
  view.  Constraint specs are plain Clojure maps; [[constraint]] builds them.
  The [[layout->]] macro provides a terse DSL:

    (layout-> root
      (= (:top header)   (:bottom nav-bar) 8)
      (= (:leading body) (:leading :safe)  0)
      (= (:width body)   (:width :parent)  0))

  Safe area access:
    `(:safe view)` — returns the safeAreaLayoutGuide of a view.

  All calls must be on the main thread."
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; NSLayoutAttribute integers (Phase 6.1)
;; =============================================================================

(def layout-attributes
  "Map of layout attribute keyword → NSLayoutAttribute integer."
  {:left       1
   :right      2
   :top        3
   :bottom     4
   :leading    5
   :trailing   6
   :width      7
   :height     8
   :center-x   9
   :center-y   10
   :baseline   11
   :not-an-attr 0})

(def layout-relations
  "Map of relation keyword → NSLayoutRelation integer."
  {:eq  0
   :lte -1
   :gte 1})

;; =============================================================================
;; constraint builder
;; =============================================================================

(defn constraint
  "Builds a constraint spec map.

  `item1` / `item2`  — ObjC view pointers (pass nil for `item2` for fixed-size constraints).
  `attr1` / `attr2`  — layout attribute keywords from [[layout-attributes]].
  `relation`         — `:eq` (default), `:lte`, or `:gte`.
  `multiplier`       — float (default 1.0).
  `constant`         — float (default 0.0).

  The constraint expresses: `item1.attr1 relation multiplier * item2.attr2 + constant`."
  [item1 attr1 relation item2 attr2
   & {:keys [multiplier constant]
      :or   {multiplier 1.0 constant 0.0}}]
  {:item1       item1
   :attr1       attr1
   :relation    relation
   :item2       item2
   :attr2       attr2
   :multiplier  (double multiplier)
   :constant    (double constant)})

;; =============================================================================
;; safe-area-guide helper
;; =============================================================================

(defn safe-area-guide
  "Returns the safeAreaLayoutGuide of `view`.
  Pass the result as item1 or item2 in a constraint spec."
  [view]
  (objc-rt/msg-send :pointer view "safeAreaLayoutGuide"))

;; =============================================================================
;; constrain! (Phase 6.1)
;; =============================================================================

(defn- attr->int [attr]
  (let [n (get layout-attributes attr)]
    (when-not n
      (throw (ex-info (str "Unknown layout attribute: " attr)
                      {:attr attr :known (keys layout-attributes)})))
    n))

(defn- relation->int [rel]
  (let [n (get layout-relations rel)]
    (when-not n
      (throw (ex-info (str "Unknown layout relation: " rel)
                      {:relation rel :known (keys layout-relations)})))
    n))

(defn- activate-constraint!
  "Creates and activates a single NSLayoutConstraint from a spec map."
  [{:keys [item1 attr1 relation item2 attr2 multiplier constant]}]
  (let [c (objc-rt/msg-send :pointer
                            (grease/get-objc-class "NSLayoutConstraint")
                            "constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:"
                            :pointer item1
                            :int64   (attr->int attr1)
                            :int64   (relation->int relation)
                            :pointer (or item2 (grease/get-objc-class "NSObject"))
                            :int64   (attr->int attr2)
                            :float64 multiplier
                            :float64 constant)]
    (objc-rt/msg-send :void c "setActive:" :int64 1)
    c))

(defn- disable-autoresizing! [view]
  (objc-rt/msg-send :void view
                    "setTranslatesAutoresizingMaskIntoConstraints:" :int64 0))

(defn constrain!
  "Creates and activates NSLayoutConstraints on `parent` from `constraint-specs`.

  Each spec is a map (see [[constraint]]) with keys:
  `:item1` `:attr1` `:relation` `:item2` `:attr2` `:multiplier` `:constant`.

  Calls `setTranslatesAutoresizingMaskIntoConstraints:NO` on every view
  referenced as `item1` in the specs.  Must be called on the main thread.

  Returns a vector of activated NSLayoutConstraint pointers."
  [parent constraint-specs]
  (disable-autoresizing! parent)
  (doseq [{:keys [item1]} constraint-specs
          :when item1]
    (disable-autoresizing! item1))
  (mapv activate-constraint! constraint-specs))

;; =============================================================================
;; layout-> macro DSL (Phase 6.1 terse form)
;; =============================================================================

;; Relation symbol → keyword
(def ^:private rel-sym->kw {'= :eq '<= :lte '>= :gte})

(defn- parse-attr-form
  "Parses `(:attr view-sym)` to {:attr :keyword :view view-sym}."
  [[attr view-sym]]
  {:attr attr :view view-sym})

(defn- parse-constraint-form
  "At macro-expand time, converts a constraint form to a constraint map form.

  Input:  `(= (:top header) (:bottom nav-bar) 8)`
  Output: `(grease.ios.layout/constraint header :top :eq nav-bar :bottom :constant 8.0)`"
  [[rel-sym attr1-form attr2-form & [constant multiplier]]]
  (let [rel      (get rel-sym->kw rel-sym)
        _        (when-not rel (throw (ex-info (str "Unknown relation: " rel-sym)
                                               {:sym rel-sym})))
        {a1 :attr v1 :view} (parse-attr-form attr1-form)
        {a2 :attr v2 :view} (parse-attr-form attr2-form)]
    `(grease.ios.layout/constraint ~v1 ~a1 ~rel ~v2 ~a2
                                   :constant ~(double (or constant 0.0))
                                   :multiplier ~(double (or multiplier 1.0)))))

(defmacro layout->
  "Applies a sequence of constraint forms to `parent`.

  Each form: `(rel (:attr1 view1) (:attr2 view2) constant)` where:
  - `rel`   — `=`, `<=`, or `>=`
  - `view`  — a symbol bound to an ObjC view pointer, or `:parent`
  - `constant` — optional float (default 0.0)

  Calls [[constrain!]] at runtime.  Must be on the main thread.

  Example:
    (layout-> root
      (= (:top header)   (:bottom nav-bar) 8.0)
      (= (:width header) (:width :parent)  0.0))"
  [parent & forms]
  `(constrain! ~parent
               ~(mapv parse-constraint-form forms)))
