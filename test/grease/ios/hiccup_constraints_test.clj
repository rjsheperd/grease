(ns grease.ios.hiccup-constraints-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.hiccup :as hiccup]))

;; Tests for Phase 6.3 — hiccup :key / :constraints integration.
;;
;; All tests cover the pure-Clojure parts (constraint tuple parsing, key
;; resolution, prop stripping).  ObjC-dependent paths (layout/constrain!,
;; setTranslatesAutoresizingMaskIntoConstraints:) are exercised on device.

;; ── constraint-key->view (pure) ───────────────────────────────────────────────

(deftest ^:parallel key->view-includes-parent-test
  (testing "constraint-key->view maps :parent to the container view"
    (let [container :fake-view
          result    (#'hiccup/constraint-key->view container [])]
      (is (= :fake-view (get result :parent))))))

(deftest ^:parallel key->view-maps-child-keys-test
  (testing "constraint-key->view maps child :key values to their view pointers"
    (let [container  :container
          child-a    {:key :header :view :view-a :tag :label :props {} :rk nil :children []}
          child-b    {:key :body   :view :view-b :tag :view  :props {} :rk nil :children []}
          no-key     {:key nil     :view :view-c :tag :view  :props {} :rk nil :children []}
          result     (#'hiccup/constraint-key->view container [child-a child-b no-key])]
      (is (= :view-a (get result :header)))
      (is (= :view-b (get result :body)))
      (is (not (contains? result nil)) "nil key must not appear"))))

;; ── parse-constraint-tuple (pure) ─────────────────────────────────────────────

(deftest ^:parallel parse-constraint-tuple-defaults-test
  (testing "parse-constraint-tuple fills default multiplier=1.0 and constant=0.0"
    (let [kv   {:parent :pv :label :lv}
          spec (#'hiccup/parse-constraint-tuple kv [:eq :label :top :parent :top])]
      (is (= :lv (:item1 spec)))
      (is (= :top (:attr1 spec)))
      (is (= :eq (:relation spec)))
      (is (= :pv (:item2 spec)))
      (is (= :top (:attr2 spec)))
      (is (= 1.0 (:multiplier spec)))
      (is (= 0.0 (:constant spec))))))

(deftest ^:parallel parse-constraint-tuple-with-constant-test
  (testing "parse-constraint-tuple honours explicit constant"
    (let [kv   {:parent :pv :header :hv}
          spec (#'hiccup/parse-constraint-tuple kv [:eq :header :top :parent :top 8.0])]
      (is (= 8.0 (:constant spec)))
      (is (= 1.0 (:multiplier spec))))))

(deftest ^:parallel parse-constraint-tuple-with-multiplier-test
  (testing "parse-constraint-tuple honours explicit multiplier"
    (let [kv   {:parent :pv :body :bv}
          spec (#'hiccup/parse-constraint-tuple kv [:eq :body :width :parent :width 0.0 0.5])]
      (is (= 0.0 (:constant spec)))
      (is (= 0.5 (:multiplier spec))))))

(deftest ^:parallel parse-constraint-tuple-unknown-item1-throws-test
  (testing "parse-constraint-tuple throws when item1-ref is missing"
    (let [kv {:parent :pv}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"item1-ref not found"
                            (#'hiccup/parse-constraint-tuple kv [:eq :ghost :top :parent :top]))))))

(deftest ^:parallel parse-constraint-tuple-unknown-item2-throws-test
  (testing "parse-constraint-tuple throws when item2-ref is missing"
    (let [kv {:parent :pv :label :lv}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"item2-ref not found"
                            (#'hiccup/parse-constraint-tuple kv [:eq :label :top :ghost :top]))))))

;; ── normalize-hiccup strips :key and :constraints ─────────────────────────────

(deftest ^:parallel normalize-strips-constraint-meta-test
  (testing "normalize-hiccup returns :key and :constraints in the raw props map"
    ;; render-tree! is responsible for stripping; verify they appear in normalized props
    ;; so the stripping logic has something to dissoc.
    (let [[tag props _] (#'hiccup/normalize-hiccup
                         [:view {:key :root :constraints [[:eq :a :top :parent :top]]}
                          [:label {:key :a :text "hi"}]])]
      (is (= :view tag))
      (is (contains? props :key))
      (is (contains? props :constraints)))))

;; ── hiccup.edn registry ───────────────────────────────────────────────────────

(deftest ^:parallel key-prop-registered-test
  (testing ":key is registered in hiccup.edn with :special :identity"
    (let [spec (#'hiccup/prop-spec :key)]
      (is (= :identity (:special spec))))))

(deftest ^:parallel constraints-prop-registered-test
  (testing ":constraints is registered in hiccup.edn with :special :identity"
    (let [spec (#'hiccup/prop-spec :constraints)]
      (is (= :identity (:special spec))))))
