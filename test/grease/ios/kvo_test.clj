(ns grease.ios.kvo-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.kvo :as kvo]))

;; ── KVORef IDeref / IRef (pure simulation) ───────────────────────────────────

(defn- make-test-ref
  "Builds a KVORef backed by a plain atom for unit-testing purposes.
  The ObjC fields (obj, keypath, observer-ptr, retain-key) are nil."
  [init]
  (kvo/->KVORef nil nil nil nil (atom init)))

(deftest ^:parallel kvoref-deref-test
  (testing "deref returns the internal atom value"
    (let [r (make-test-ref 42)]
      (is (= 42 @r)))))

(deftest ^:parallel kvoref-add-watch-test
  (testing "add-watch fires when internal atom is reset"
    (let [r       (make-test-ref 0)
          changes (atom [])]
      (add-watch r ::test (fn [_ _ old new]
                            (swap! changes conj [old new])))
      ;; simulate a KVO callback updating the atom
      (reset! (.-internal r) 99)
      (is (= [[0 99]] @changes)))))

(deftest ^:parallel kvoref-remove-watch-test
  (testing "remove-watch stops firing"
    (let [r       (make-test-ref 0)
          changes (atom [])]
      (add-watch r ::test (fn [_ _ old new]
                            (swap! changes conj [old new])))
      (remove-watch r ::test)
      (reset! (.-internal r) 5)
      (is (= [] @changes)))))

;; ── reaction (pure) ──────────────────────────────────────────────────────────

(deftest ^:parallel reaction-initial-value-test
  (testing "reaction returns a derived atom with the initial value"
    (let [a  (atom 10)
          r  (kvo/reaction #(* @a 2) a)]
      (is (= 20 @r)))))

(deftest ^:parallel reaction-updates-on-input-change-test
  (testing "reaction re-evaluates when a source observable changes"
    (let [a (atom 3)
          r (kvo/reaction #(+ @a 1) a)]
      (reset! a 9)
      (is (= 10 @r)))))

(deftest ^:parallel reaction-no-spurious-update-test
  (testing "reaction does not fire watches when derived value is unchanged"
    (let [a      (atom 5)
          r      (kvo/reaction #(even? @a) a)
          fires  (atom 0)]
      (add-watch r ::test (fn [_ _ _ _] (swap! fires inc)))
      ;; change a but keep even? result the same (false → false)
      (reset! a 7)
      (is (= 0 @fires) "no watch fire when derived value unchanged"))))

(deftest ^:parallel reaction-multiple-sources-test
  (testing "reaction re-evaluates when any source changes"
    (let [a (atom 1)
          b (atom 2)
          r (kvo/reaction #(+ @a @b) a b)]
      (reset! a 10)
      (is (= 12 @r))
      (reset! b 20)
      (is (= 30 @r)))))
