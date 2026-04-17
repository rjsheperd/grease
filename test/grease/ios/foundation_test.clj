(ns grease.ios.foundation-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.foundation :as f]
            [grease.ios.retain :as retain]))

;; ── with-retained ────────────────────────────────────────────────────────────

(deftest ^:parallel with-retained-binds-test
  (testing "bindings are accessible in body"
    (let [result (f/with-retained [x 42 y :hello]
                   [x y])]
      (is (= [42 :hello] result)))))

(deftest ^:parallel with-retained-populates-registry-test
  (testing "retain registry is populated during body execution"
    (retain/reset-all!)
    (f/with-retained [_x :a]
      (is (= 1 (retain/retained-count))
          "registry should have one entry while in body"))
    (is (= 0 (retain/retained-count))
        "registry should be empty after body completes")))

(deftest ^:parallel with-retained-cleanup-on-throw-test
  (testing "retain registry is cleaned up even when body throws"
    (retain/reset-all!)
    (is (thrown? Exception
                 (f/with-retained [_x :a]
                   (throw (Exception. "intentional")))))
    (is (= 0 (retain/retained-count))
        "registry should be empty after exception")))

(deftest ^:parallel with-retained-multiple-bindings-test
  (testing "multiple bindings are all retained then released"
    (retain/reset-all!)
    (f/with-retained [_a 1 _b 2 _c 3]
      (is (= 3 (retain/retained-count))))
    (is (= 0 (retain/retained-count)))))

(deftest ^:parallel with-retained-returns-body-test
  (testing "with-retained returns the value of the last body form"
    (is (= :result
           (f/with-retained [_x 1]
             :ignored
             :result)))))

;; ── with-autorelease macroexpansion ─────────────────────────────────────────

(deftest ^:parallel with-autorelease-expansion-test
  (testing "macroexpands to let/try/finally structure"
    (let [expanded (macroexpand-1 '(grease.ios.foundation/with-autorelease
                                     (inc 1)))]
      (is (= 'clojure.core/let (first expanded))
          "outer form is let")
      ;; The try form is the third element of the let (after bindings vector)
      (let [try-form (nth expanded 2)]
        (is (= 'try (first try-form))
            "body is wrapped in try")
        (is (some #(and (seq? %) (= 'finally (first %))) (rest try-form))
            "try includes a finally clause")))))
