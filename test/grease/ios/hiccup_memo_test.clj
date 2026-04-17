(ns grease.ios.hiccup-memo-test
  "Layer 4 — `hiccup/memo` helper tests.

  All tests are pure Clojure; no ObjC hardware required."
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.hiccup :as hiccup]))

;; =============================================================================
;; Deps unchanged → same reference returned
;; =============================================================================

(deftest ^:parallel memo-same-deps-returns-identical-reference-test
  (testing "unchanged deps → memo returns the identical cached hiccup reference"
    (let [deps-atom (atom [1 2 3])
          body-fn   #(vector :label {:text "hi"})
          render    (hiccup/memo #(deref deps-atom) body-fn)
          first-result  (render)
          second-result (render)]
      (is (identical? first-result second-result)
          "same deps must return the identical cached hiccup vector"))))

;; =============================================================================
;; Deps changed → new reference, body-fn called again
;; =============================================================================

(deftest ^:parallel memo-changed-deps-returns-new-reference-test
  (testing "changed deps → memo returns a new hiccup value"
    (let [deps-atom (atom [1])
          body-fn   #(vector :label {:text (str @deps-atom)})
          render    (hiccup/memo #(deref deps-atom) body-fn)
          first-result  (render)
          _             (reset! deps-atom [2])
          second-result (render)]
      (is (not (identical? first-result second-result))
          "different deps must produce a new hiccup reference"))))

;; =============================================================================
;; body-fn call count matches dep-change count
;; =============================================================================

(deftest ^:parallel memo-body-fn-call-count-test
  (testing "body-fn is called exactly once per distinct deps value"
    (let [call-count (atom 0)
          deps-atom  (atom :a)
          body-fn    #(do (swap! call-count inc)
                          [:label {:text (str @deps-atom)}])
          render     (hiccup/memo #(deref deps-atom) body-fn)]
      ;; First call — deps ::unset → :a, body-fn fires once.
      (render)
      (is (= 1 @call-count) "body-fn called once after first render")
      ;; Same deps — no re-evaluation.
      (render)
      (render)
      (is (= 1 @call-count) "body-fn not called again while deps unchanged")
      ;; Deps change to :b — fires once.
      (reset! deps-atom :b)
      (render)
      (is (= 2 @call-count) "body-fn called once after deps change to :b")
      ;; Deps change to :c — fires once.
      (reset! deps-atom :c)
      (render)
      (is (= 3 @call-count) "body-fn called once after deps change to :c"))))

;; =============================================================================
;; Initial render fires body-fn (::unset sentinel)
;; =============================================================================

(deftest ^:parallel memo-initial-render-fires-body-fn-test
  (testing "first call always evaluates body-fn (cache starts as ::unset)"
    (let [call-count (atom 0)
          render     (hiccup/memo (constantly []) #(do (swap! call-count inc) [:view]))]
      (render)
      (is (= 1 @call-count)
          "body-fn must be called on the very first render"))))
