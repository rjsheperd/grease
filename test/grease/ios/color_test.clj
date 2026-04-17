(ns grease.ios.color-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.color :as color]))

;; ── parse-hex (pure, no ObjC) ───────────────────────────────────────────────
;; Access the private function via var interning for unit tests.
(def ^:private parse-hex #'color/parse-hex)

(deftest ^:parallel hex-6-digit-test
  (testing "6-digit hex parses to [r g b 1.0]"
    (let [[r g b a] (parse-hex "#FF0000")]
      (is (= 1.0 r))
      (is (= 0.0 g))
      (is (= 0.0 b))
      (is (= 1.0 a)))))

(deftest ^:parallel hex-8-digit-test
  (testing "8-digit hex parses to [r g b a]"
    (let [[r g b a] (parse-hex "#FF000080")]
      (is (= 1.0 r))
      (is (= 0.0 g))
      (is (= 0.0 b))
      (is (< 0.49 a 0.51)))))

(deftest ^:parallel hex-no-hash-test
  (testing "hex string without # prefix is accepted"
    (let [[r g b a] (parse-hex "00FF00")]
      (is (= 0.0 r))
      (is (= 1.0 g))
      (is (= 0.0 b))
      (is (= 1.0 a)))))

(deftest ^:parallel hex-3-digit-test
  (testing "3-digit shorthand expands each nibble"
    (let [[r g b a] (parse-hex "#FFF")]
      (is (= 1.0 r))
      (is (= 1.0 g))
      (is (= 1.0 b))
      (is (= 1.0 a)))))

(deftest ^:parallel hex-invalid-returns-nil-test
  (testing "unrecognised hex length returns nil"
    (is (nil? (parse-hex "#FFFFF")))
    (is (nil? (parse-hex "ZZZZZ")))))

(deftest ^:parallel hex-teal-test
  (testing "Clojure teal #3FBCBC round-trips correctly"
    (let [[r g b a] (parse-hex "#3FBCBC")]
      (is (< 0.24 r 0.26))
      (is (< 0.73 g 0.75))
      (is (< 0.73 b 0.75))
      (is (= 1.0 a)))))

;; ── ->uicolor bad input ──────────────────────────────────────────────────────

(deftest ^:parallel unknown-keyword-throws-test
  (testing "->uicolor throws ex-info for an unknown keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown color keyword"
                          (color/->uicolor :not-a-real-color)))))

(deftest ^:parallel bad-hex-throws-test
  (testing "->uicolor throws ex-info for a malformed hex string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unrecognised hex"
                          (color/->uicolor "#ZZZZZZ")))))

(deftest ^:parallel unrecognised-type-throws-test
  (testing "->uicolor throws ex-info for an unrecognised type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot coerce"
                          (color/->uicolor 42)))))
