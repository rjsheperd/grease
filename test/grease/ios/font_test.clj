(ns grease.ios.font-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.font :as font]))

;; ── Error paths (pure, no ObjC) ─────────────────────────────────────────────

(deftest ^:parallel unknown-keyword-throws-test
  (testing "->uifont throws ex-info for an unknown keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown font keyword"
                          (font/->uifont [:not-a-font 16])))))

(deftest ^:parallel bad-vector-first-throws-test
  (testing "->uifont throws ex-info when the first vector element is not a keyword or string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot coerce"
                          (font/->uifont [42 16])))))

(deftest ^:parallel bad-type-throws-test
  (testing "->uifont throws ex-info for an unrecognised type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot coerce"
                          (font/->uifont {:size 16})))))

;; ── Selector table sanity ────────────────────────────────────────────────────

(deftest ^:parallel known-keywords-test
  (testing "all documented font keywords exist in the selector table"
    (let [table @#'font/font-selectors]
      (doseq [kw [:system :system-bold :system-italic :monospaced]]
        (is (contains? table kw) (str kw " missing from font-selectors"))))))
