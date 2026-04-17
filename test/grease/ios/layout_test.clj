(ns grease.ios.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.layout :as layout]))

;; ── Attribute and relation tables (pure) ────────────────────────────────────

(deftest ^:parallel layout-attributes-complete-test
  (testing "layout-attributes contains all required keys"
    (doseq [k [:left :right :top :bottom :leading :trailing
               :width :height :center-x :center-y :baseline]]
      (is (contains? layout/layout-attributes k)
          (str k " missing from layout-attributes")))))

(deftest ^:parallel layout-attributes-values-test
  (testing "layout-attributes has expected NSLayoutAttribute integers"
    (is (= 1 (:left layout/layout-attributes)))
    (is (= 3 (:top layout/layout-attributes)))
    (is (= 7 (:width layout/layout-attributes)))))

(deftest ^:parallel attr->int-unknown-throws-test
  (testing "unknown attribute throws ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown layout attribute"
                          (#'layout/attr->int :not-an-attr-key)))))

(deftest ^:parallel relation->int-test
  (testing "relation keywords map to correct NSLayoutRelation integers"
    (is (= 0  (#'layout/relation->int :eq)))
    (is (= -1 (#'layout/relation->int :lte)))
    (is (= 1  (#'layout/relation->int :gte)))))

;; ── constraint builder (pure) ────────────────────────────────────────────────

(deftest ^:parallel constraint-defaults-test
  (testing "constraint returns a map with default multiplier=1.0 constant=0.0"
    (let [spec (layout/constraint :v1 :top :eq :v2 :bottom)]
      (is (= :v1 (:item1 spec)))
      (is (= :top (:attr1 spec)))
      (is (= :eq (:relation spec)))
      (is (= :v2 (:item2 spec)))
      (is (= :bottom (:attr2 spec)))
      (is (= 1.0 (:multiplier spec)))
      (is (= 0.0 (:constant spec))))))

(deftest ^:parallel constraint-with-opts-test
  (testing "constraint accepts :constant and :multiplier options"
    (let [spec (layout/constraint :v1 :width :eq :v2 :width
                                  :multiplier 0.5 :constant 10.0)]
      (is (= 0.5 (:multiplier spec)))
      (is (= 10.0 (:constant spec))))))

;; ── layout-> macroexpansion (pure) ───────────────────────────────────────────

(deftest ^:parallel layout->-expands-to-constrain!-test
  (testing "layout-> expands to a constrain! call"
    (let [expanded (macroexpand-1
                    '(grease.ios.layout/layout-> root
                                                 (= (:top a) (:bottom b) 8.0)))]
      (is (= "constrain!" (name (first expanded)))
          "expansion calls constrain!"))))

(deftest ^:parallel layout->-empty-test
  (testing "layout-> with no forms expands to constrain! with empty vector"
    (let [expanded (macroexpand-1
                    '(grease.ios.layout/layout-> root))]
      (is (= [] (nth expanded 2))
          "third element is empty constraints vector"))))
