(ns grease.ios.hiccup-keyed-children-test
  "Layer 2 — keyed child diffing tests.

  Tests the `reconcile-children!` private function via `#'` access.
  All tests use `with-redefs` to stub ObjC bridge calls.  No iOS hardware needed."
  (:require [clojure.test :refer [deftest is testing]]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain])
  (:import [tech.v3.datatype.ffi Pointer]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- fake-ptr
  "Returns a distinct Pointer so `.address` calls don't fail."
  [addr]
  (Pointer. addr))

(defn- make-node
  "Builds a minimal rendered-node map with a fake retain key."
  [tag key-kw addr]
  (let [view (fake-ptr addr)
        rk   (retain/retain! (gensym "kc-") view ::test)]
    {:tag tag :props {} :key key-kw :view view :rk rk
     :children [] :hiccup nil}))

(def ^:private call-log (atom []))

(defn- with-stubs
  "Runs body-fn with ObjC stubs installed. Clears and returns call-log."
  [body-fn]
  (reset! call-log [])
  (with-redefs [objc-rt/msg-send         (fn [& args]
                                           (swap! call-log conj (vec args))
                                            ;; Return a vector of subviews when \"subviews\" is called.
                                           (when (= "subviews" (nth args 2 nil)) []))
                f/null-ptr               (constantly ::null-ptr)
                f/nsarray->vec           (fn [x] (if (vector? x) x []))
                grease/register-objc-sel (constantly ::sel)
                grease/get-objc-class    (constantly nil)
                grease/objc-new          (constantly (fake-ptr 0))]
    (body-fn))
  @call-log)

(defn- calls-for-sel
  "Filters call-log entries by selector string."
  [log sel]
  (filter #(= sel (nth % 2)) log))

;; =============================================================================
;; Positional algorithm (no keys) — regression guard
;; =============================================================================

(deftest ^:parallel positional-reconcile-no-keys-test
  (testing "positional algorithm fires when no children have :key"
    (let [parent (fake-ptr 0x100)
          a      (make-node :label nil 0x200)
          b      (make-node :label nil 0x300)
          log    (with-stubs
                   #(#'hiccup/reconcile-children! parent [a b]
                                                  [[:label {:alpha 1.0}]
                                                   [:label {:alpha 0.5}]]))]
      ;; No insertSubview:atIndex: for positional reconcile.
      (is (empty? (calls-for-sel log "insertSubview:atIndex:"))
          "no reorder calls for positional (no keys) algorithm"))))

;; =============================================================================
;; Reorder keyed children — no addSubview:
;; =============================================================================

(deftest ^:parallel keyed-reorder-no-add-subview-test
  (testing "reordering [:a :b :c] → [:b :a :c] emits insertSubview:atIndex: not addSubview:"
    (let [parent (fake-ptr 0x100)
          n-a    (make-node :view :a 0x200)
          n-b    (make-node :view :b 0x300)
          n-c    (make-node :view :c 0x400)
          ;; Simulate current subview order: a, b, c
          subviews-ordered [(:view n-a) (:view n-b) (:view n-c)]]
      (with-redefs [objc-rt/msg-send   (fn [& args]
                                         (swap! call-log conj (vec args))
                                         (when (= "subviews" (nth args 2 nil))
                                           subviews-ordered))
                    f/nsarray->vec     (fn [x] (if (vector? x) x []))
                    f/null-ptr         (constantly ::null-ptr)
                    grease/register-objc-sel (constantly ::sel)]
        (reset! call-log [])
        (#'hiccup/reconcile-children! parent
                                      [n-a n-b n-c]
                                      [[:view {:key :b}]
                                       [:view {:key :a}]
                                       [:view {:key :c}]]))
      (let [log @call-log]
        (is (empty? (calls-for-sel log "addSubview:"))
            "addSubview: must NOT be called when reordering keyed children")
        (is (seq (calls-for-sel log "insertSubview:atIndex:"))
            "insertSubview:atIndex: must be called to reorder")))))

;; =============================================================================
;; Insert at front — only 1 addSubview: call
;; =============================================================================

(deftest ^:parallel keyed-insert-new-child-test
  (testing "inserting a new keyed child results in exactly 1 addSubview: call"
    (let [parent   (fake-ptr 0x100)
          n-a      (make-node :label :a 0x200)
          n-b      (make-node :label :b 0x300)]
      ;; Old children: [:a :b]; new children: [:new :a :b]
      ;; :new has no matching old node → render-tree! → addSubview:
      (with-redefs [objc-rt/msg-send   (fn [& args]
                                         (swap! call-log conj (vec args))
                                         ;; Return a fake Pointer for "new" (view creation)
                                         ;; so vary-meta in create-element does not NPE.
                                         (condp = (nth args 2 nil)
                                           "new"      (fake-ptr 0x999)
                                           "subviews" []
                                           nil))
                    f/nsarray->vec     (fn [x] (if (vector? x) x []))
                    f/null-ptr         (constantly ::null-ptr)
                    grease/register-objc-sel (constantly ::sel)
                    grease/get-objc-class    (constantly (fake-ptr 0x001))]
        (reset! call-log [])
        (#'hiccup/reconcile-children! parent
                                      [n-a n-b]
                                      [[:label {:key :new}]
                                       [:label {:key :a}]
                                       [:label {:key :b}]]))
      (let [log @call-log]
        (is (= 1 (count (calls-for-sel log "addSubview:")))
            "exactly 1 addSubview: for the single new keyed child")))))

;; =============================================================================
;; Remove one keyed child — removeFromSuperview fires once
;; =============================================================================

(deftest ^:parallel keyed-remove-child-test
  (testing "removing a keyed child calls removeFromSuperview exactly once"
    (let [parent (fake-ptr 0x100)
          n-a    (make-node :view :a 0x200)
          n-b    (make-node :view :b 0x300)
          n-c    (make-node :view :c 0x400)]
      ;; Old: [:a :b :c]; New: [:a :c] — :b is removed
      (with-redefs [objc-rt/msg-send   (fn [& args]
                                         (swap! call-log conj (vec args))
                                         (when (= "subviews" (nth args 2 nil)) []))
                    f/nsarray->vec     (fn [x] (if (vector? x) x []))
                    f/null-ptr         (constantly ::null-ptr)
                    grease/register-objc-sel (constantly ::sel)]
        (reset! call-log [])
        (#'hiccup/reconcile-children! parent
                                      [n-a n-b n-c]
                                      [[:view {:key :a}]
                                       [:view {:key :c}]]))
      (let [log            @call-log
            remove-calls   (calls-for-sel log "removeFromSuperview")]
        (is (= 1 (count remove-calls))
            "removeFromSuperview must fire exactly once for the removed keyed child")))))

;; =============================================================================
;; Mixed keyed + unkeyed children
;; =============================================================================

(deftest ^:parallel mixed-keyed-unkeyed-test
  (testing "keyed children match by key; unkeyed match by position"
    (let [parent     (fake-ptr 0x100)
          n-keyed    (make-node :view :k 0x200)
          n-unkeyed1 (make-node :label nil 0x300)
          n-unkeyed2 (make-node :label nil 0x400)]
      ;; Old: [:k (unkeyed) (unkeyed)]
      ;; New: [:k (unkeyed-1)] — one unkeyed dropped
      (with-redefs [objc-rt/msg-send   (fn [& args]
                                         (swap! call-log conj (vec args))
                                         (when (= "subviews" (nth args 2 nil)) []))
                    f/nsarray->vec     (fn [x] (if (vector? x) x []))
                    f/null-ptr         (constantly ::null-ptr)
                    grease/register-objc-sel (constantly ::sel)]
        (reset! call-log [])
        (#'hiccup/reconcile-children! parent
                                      [n-keyed n-unkeyed1 n-unkeyed2]
                                      [[:view {:key :k}]
                                       [:label {}]]))
      (let [log          @call-log
            remove-calls (calls-for-sel log "removeFromSuperview")]
        ;; n-unkeyed2 should be torn down (extra unkeyed).
        (is (= 1 (count remove-calls))
            "the extra unkeyed child must be torn down")))))
