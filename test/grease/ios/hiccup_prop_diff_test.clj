(ns grease.ios.hiccup-prop-diff-test
  "Layer 3 — prop removal defaults + event-handler dedup tests.

  All tests run on the JVM using `with-redefs` to stub ObjC bridge
  calls.  No iOS hardware required."
  (:require [clojure.test :refer [deftest is testing]]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.objc :as objc-rt])
  (:import [tech.v3.datatype.ffi Pointer]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- capture-calls
  "Runs `body-fn` with `objc-rt/msg-send` and `f/null-ptr` stubbed.
  Returns a vector of recorded argument lists (each as a vector)."
  [body-fn]
  (let [log (atom [])]
    (with-redefs [objc-rt/msg-send (fn [& args] (swap! log conj (vec args)) nil)
                  f/null-ptr       (constantly ::null-ptr)
                  grease/register-objc-sel (constantly ::sel-handle-event)]
      (body-fn))
    @log))

(defn- calls-for-sel
  "Filters call-log entries whose third element (selector string) equals `sel`."
  [log sel]
  (filter #(= sel (nth % 2)) log))

;; =============================================================================
;; Prop removal resets to default
;; =============================================================================

(deftest ^:parallel prop-removal-alpha-resets-to-default-test
  (testing "removing :alpha from new props resets view to default 1.0"
    (let [view         (Object.)
          old-node     {:tag :label :props {:alpha 0.5}
                        :key nil :view view :rk nil :children []}
          log          (capture-calls
                        #(#'hiccup/reconcile! nil old-node [:label {}]))
          alpha-calls  (calls-for-sel log "setAlpha:")]
      (is (seq alpha-calls) "setAlpha: must be called to reset default")
      (is (= :float64 (nth (first alpha-calls) 3)))
      (is (= 1.0 (nth (first alpha-calls) 4))))))

(deftest ^:parallel prop-removal-hidden-resets-to-default-test
  (testing "removing :hidden from new props resets view to default 0"
    (let [view          (Object.)
          old-node      {:tag :view :props {:hidden 1}
                         :key nil :view view :rk nil :children []}
          log           (capture-calls
                         #(#'hiccup/reconcile! nil old-node [:view {}]))
          hidden-calls  (calls-for-sel log "setHidden:")]
      (is (seq hidden-calls) "setHidden: must be called to reset default")
      (is (= :int64 (nth (first hidden-calls) 3)))
      (is (= 0 (nth (first hidden-calls) 4))))))

(deftest ^:parallel prop-removal-corner-resets-to-default-test
  (testing "removing :corner from new props resets layer corner radius to 0.0"
    (let [view          (Object.)
          old-node      {:tag :view :props {:corner 8.0}
                         :key nil :view view :rk nil :children []}
          log           (capture-calls
                         #(#'hiccup/reconcile! nil old-node [:view {}]))
          corner-calls  (calls-for-sel log "setCornerRadius:")]
      (is (seq corner-calls) "setCornerRadius: must be called to reset default")
      (is (= 0.0 (nth (first corner-calls) 4))))))

;; =============================================================================
;; Prop unchanged → no bridge call
;; =============================================================================

(deftest ^:parallel prop-unchanged-no-bridge-call-test
  (testing "unchanged prop value does not trigger a bridge call"
    (let [view         (Object.)
          old-node     {:tag :label :props {:alpha 0.5}
                        :key nil :view view :rk nil :children []}
          log          (capture-calls
                        #(#'hiccup/reconcile! nil old-node [:label {:alpha 0.5}]))
          alpha-calls  (calls-for-sel log "setAlpha:")]
      (is (empty? alpha-calls)
          "setAlpha: must NOT be called when value is unchanged"))))

;; =============================================================================
;; :on-tap dedup — wire-event! called once per reconcile
;; =============================================================================

(deftest ^:parallel on-tap-dedup-wire-once-test
  (testing ":on-tap in both old and new props — addTarget fires exactly once"
    ;; wire-event! calls .address on the view; use a real Pointer so reflection works.
    (let [view          (Pointer. 0x1000)
          old-node      {:tag :button :props {:on-tap (fn [] :old)}
                         :key nil :view view :rk nil :children []}
          log           (capture-calls
                         #(#'hiccup/reconcile! nil old-node
                                               [:button {:on-tap (fn [] :new)}]))
          add-calls     (calls-for-sel log "addTarget:action:forControlEvents:")
          remove-calls  (calls-for-sel log "removeTarget:action:forControlEvents:")]
      (is (= 1 (count add-calls))
          "addTarget:action:forControlEvents: must fire exactly once")
      (is (= 1 (count remove-calls))
          "removeTarget:action:forControlEvents: must fire exactly once for dedup"))))

;; =============================================================================
;; :key and :constraints removal silently skipped
;; =============================================================================

(deftest ^:parallel key-not-in-stored-props-test
  (testing ":key is stripped by render-tree! and never stored in :props"
    (let [spec (#'hiccup/prop-spec :key)]
      (is (= :identity (:special spec))
          ":key must have :special :identity so removal pass skips it"))))

(deftest ^:parallel constraints-not-in-stored-props-test
  (testing ":constraints is stripped by render-tree! and never stored in :props"
    (let [spec (#'hiccup/prop-spec :constraints)]
      (is (= :identity (:special spec))
          ":constraints must have :special :identity so removal pass skips it"))))

(deftest ^:parallel no-props-changed-no-bridge-calls-test
  (testing "no bridge calls when both old and new props are empty"
    (let [view      (Object.)
          old-node  {:tag :view :props {}
                     :key nil :view view :rk nil :children []}
          log       (capture-calls
                     #(#'hiccup/reconcile! nil old-node [:view {}]))]
      (is (empty? log)
          "no bridge calls when no props changed and none to remove"))))
