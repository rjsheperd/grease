(ns grease.ios.hiccup-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.hiccup :as hiccup]))

;; ── EDN registry (pure) ──────────────────────────────────────────────────────

(deftest ^:parallel registry-tags-exist-test
  (testing "hiccup.edn contains expected view tags"
    (let [tags (keys (:tags @@#'hiccup/registry))]
      (doseq [t [:view :label :button :stack :scroll :field]]
        (is (contains? (set tags) t)
            (str t " missing from hiccup registry"))))))

(deftest ^:parallel registry-props-exist-test
  (testing "hiccup.edn contains expected prop keys"
    (let [props (keys (:props @@#'hiccup/registry))]
      (doseq [p [:bg :text :font :frame :alpha :on-tap]]
        (is (contains? (set props) p)
            (str p " missing from props registry"))))))

(deftest ^:parallel label-tag-spec-test
  (testing "label tag spec has UILabel class"
    (let [spec (#'hiccup/tag-spec :label)]
      (is (= "UILabel" (:class spec))))))

(deftest ^:parallel button-tag-spec-test
  (testing "button tag spec has UIButton class and factory"
    (let [spec (#'hiccup/tag-spec :button)]
      (is (= "UIButton" (:class spec)))
      (is (= "buttonWithType:" (:factory spec))))))

(deftest ^:parallel unknown-tag-throws-test
  (testing "unknown tag throws ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown hiccup tag"
                          (#'hiccup/tag-spec :not-a-view)))))

(deftest ^:parallel bg-prop-spec-test
  (testing ":bg prop has correct selector and coerce"
    (let [spec (#'hiccup/prop-spec :bg)]
      (is (= "setBackgroundColor:" (:sel spec)))
      (is (= :ui-color (:coerce spec))))))

(deftest ^:parallel on-tap-prop-spec-test
  (testing ":on-tap prop has control-event 64"
    (let [spec (#'hiccup/prop-spec :on-tap)]
      (is (= 64 (:control-event spec))))))

;; ── normalize-hiccup (pure) ──────────────────────────────────────────────────

(deftest ^:parallel normalize-with-props-test
  (testing "normalize-hiccup extracts tag, props map, and children"
    (let [[tag props children] (#'hiccup/normalize-hiccup
                                [:label {:text "hi" :alpha 0.5} [:view]])]
      (is (= :label tag))
      (is (= {:text "hi" :alpha 0.5} props))
      (is (= [[:view]] children)))))

(deftest ^:parallel normalize-without-props-test
  (testing "normalize-hiccup supplies empty props when first child is not a map"
    (let [[tag props children] (#'hiccup/normalize-hiccup [:label "hello"])]
      (is (= :label tag))
      (is (= {} props))
      (is (= ["hello"] children)))))

(deftest ^:parallel normalize-bare-tag-test
  (testing "normalize-hiccup handles bare tag with no props or children"
    (let [[tag props children] (#'hiccup/normalize-hiccup [:view])]
      (is (= :view tag))
      (is (= {} props))
      (is (nil? children)))))
