(ns grease.ios.anim-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.anim :as anim]))

;; ── Curve options (pure) ─────────────────────────────────────────────────────

(deftest ^:parallel resolve-options-ease-in-out-test
  (testing "default curve :ease-in-out → 0x0"
    (is (= 0x00000000 (#'anim/resolve-options {:duration 0.3})))))

(deftest ^:parallel resolve-options-linear-test
  (testing ":linear → 0x00030000"
    (is (= 0x00030000 (#'anim/resolve-options {:duration 0.3 :curve :linear})))))

(deftest ^:parallel resolve-options-ease-in-test
  (testing ":ease-in → 0x00010000"
    (is (= 0x00010000 (#'anim/resolve-options {:duration 0.3 :curve :ease-in})))))

(deftest ^:parallel unknown-curve-throws-test
  (testing "unknown curve throws ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown animation curve"
                          (#'anim/resolve-options {:duration 0.3 :curve :bounce})))))

;; ── anim-> macroexpansion (pure) ─────────────────────────────────────────────

(deftest ^:parallel anim->-expands-to-fn-test
  (testing "anim-> expands to a fn [] form"
    (let [expanded (macroexpand-1 '(grease.ios.anim/anim-> my-view (alpha 0.5)))]
      (is (= "fn" (name (first expanded)))
          "outer form is fn")
      (is (= [] (second expanded))
          "fn takes zero args"))))

(deftest ^:parallel anim->-expands-alpha-test
  (testing "alpha op expands to set-alpha! call"
    (let [expanded (macroexpand-1 '(grease.ios.anim/anim-> my-view (alpha 0.5)))
          body     (nth expanded 2)]
      (is (= 'grease.ios.anim/set-alpha! (first body)))
      (is (= 'my-view (second body)))
      (is (= 0.5 (nth body 2))))))

(deftest ^:parallel anim->-multiple-ops-test
  (testing "multiple ops expand to multiple setter calls"
    (let [expanded (macroexpand-1 '(grease.ios.anim/anim-> v
                                                           (alpha 0.0)
                                                           (bg :red)))
          calls    (drop 2 expanded)]
      (is (= 2 (count calls)))
      (is (= 'grease.ios.anim/set-alpha! (ffirst calls)))
      (is (= 'grease.ios.anim/set-bg! (first (second calls)))))))
