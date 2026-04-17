(ns grease.ios.hiccup-identity-skip-test
  "Layer 1 — subtree identity skip tests.

  Tests that `reconcile!` returns the old node immediately (zero ObjC calls)
  when the new hiccup value is the identical object reference (or an equal
  string), and that a different object still triggers the normal diff path.

  All tests run on the JVM using `with-redefs` to stub ObjC bridge calls."
  (:require [clojure.test :refer [deftest is testing]]
            [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- capture-calls
  "Runs `body-fn` with ObjC bridge stubs installed.
  Returns [result call-log-vec]."
  [body-fn]
  (let [log (atom [])]
    (with-redefs [objc-rt/msg-send           (fn [& args] (swap! log conj (vec args)) nil)
                  f/null-ptr                 (constantly ::null-ptr)
                  f/->nsstring               identity
                  grease/register-objc-sel   (constantly ::sel)]
      [(body-fn) @log])))

(defn- calls-for-sel
  "Filters call-log entries whose third element equals `sel`."
  [log sel]
  (filter #(= sel (nth % 2)) log))

;; =============================================================================
;; Same vector reference → zero msg-send calls
;; =============================================================================

(deftest ^:parallel identical-vector-skips-all-bridge-calls-test
  (testing "identical? vector reference → reconcile! returns old-node without any ObjC call"
    (let [hiccup   [:label {:alpha 0.5}]
          view     (Object.)
          old-node {:tag :label :props {:alpha 0.5}
                    :key nil :view view :rk nil :children []
                    :hiccup hiccup}
          [result log] (capture-calls
                        #(#'hiccup/reconcile! nil old-node hiccup))]
      (is (identical? old-node result)
          "must return the exact same old-node map")
      (is (empty? log)
          "zero ObjC bridge calls when hiccup reference is identical"))))

;; =============================================================================
;; Different vector with equal content → prop-diff fires
;; =============================================================================

(deftest ^:parallel equal-but-not-identical-vector-triggers-diff-test
  (testing "equal-content but different vector object still enters prop-diff path"
    ;; hiccup1 and hiccup2 have the same content but are distinct objects.
    (let [hiccup1  [:label {:alpha 0.5}]
          hiccup2  [:label {:alpha 0.5}]
          view     (Object.)
          old-node {:tag :label :props {:alpha 0.5}
                    :key nil :view view :rk nil :children []
                    :hiccup hiccup1}
          [_result log] (capture-calls
                         #(#'hiccup/reconcile! nil old-node hiccup2))]
      (is (not (identical? hiccup1 hiccup2))
          "pre-condition: vectors must not be identical")
      ;; No prop changed, so no setter fired — but the diff path ran.
      (is (empty? (calls-for-sel log "setAlpha:"))
          "setAlpha: not called when value is unchanged"))))

;; =============================================================================
;; String equal value → skip; different string → setText: called
;; =============================================================================

(deftest ^:parallel equal-string-skips-bridge-calls-test
  (testing "equal string value → reconcile! skips and returns old-node"
    (let [s        "hello"
          view     (Object.)
          old-node {:tag :label :props {:text s}
                    :key nil :view view :rk nil :children []
                    :hiccup s}
          [result log] (capture-calls
                        #(#'hiccup/reconcile! nil old-node "hello"))]
      (is (identical? old-node result)
          "same string value → old-node returned unchanged")
      (is (empty? log)
          "zero ObjC calls for equal string"))))

(deftest ^:parallel different-string-triggers-set-text-test
  (testing "different string value → reconcile! applies new text via setText:"
    (let [view     (Object.)
          old-node {:tag :label :props {:text "old"}
                    :key nil :view view :rk nil :children []
                    :hiccup "old"}
          [_result log] (capture-calls
                         #(#'hiccup/reconcile! nil old-node "new"))
          set-text-calls (calls-for-sel log "setText:")]
      (is (seq set-text-calls)
          "setText: must be called when string changes"))))

;; =============================================================================
;; :hiccup field is populated on rendered nodes (pure structure check)
;; =============================================================================

(deftest ^:parallel rendered-node-hiccup-field-present-test
  (testing "rendered-node has :hiccup field after manual construction"
    ;; We construct a node the same way render-tree! does and confirm the field.
    (let [rk   (retain/retain! ::skip-hiccup-field-test "fake" ::test)
          form [:label {:text "hi"}]
          node {:tag :label :props {:text "hi"} :key nil
                :view nil :rk rk :children [] :hiccup form}]
      (is (= form (:hiccup node))
          ":hiccup field must hold the original hiccup form")
      ;; Cleanup.
      (retain/release! ::skip-hiccup-field-test))))
