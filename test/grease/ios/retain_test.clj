(ns grease.ios.retain-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.retain :as retain]))

;; Reset the registry before each test so tests are isolated.
(use-fixtures :each (fn [f] (retain/reset-all!) (f)))

;; ── retain! ─────────────────────────────────────────────────────────────────

(deftest ^:parallel retain-returns-key-test
  (testing "retain! returns the supplied key"
    (is (= ::my-key (retain/retain! ::my-key :fake-ptr :delegate)))))

(deftest ^:parallel retain-stores-ptr-test
  (testing "retained returns the ptr stored by retain!"
    (retain/retain! ::a :ptr-a :observer)
    (is (= :ptr-a (retain/retained ::a)))))

(deftest ^:parallel retain-missing-key-test
  (testing "retained returns nil for an unregistered key"
    (is (nil? (retain/retained ::never-registered)))))

(deftest ^:parallel retain-overwrites-test
  (testing "retain! replaces an existing entry for the same key"
    (retain/retain! ::k :old-ptr :delegate)
    (retain/retain! ::k :new-ptr :delegate)
    (is (= :new-ptr (retain/retained ::k)))))

;; ── release! ────────────────────────────────────────────────────────────────

(deftest ^:parallel release-returns-ptr-test
  (testing "release! returns the previously retained ptr"
    (retain/retain! ::b :ptr-b :manager)
    (is (= :ptr-b (retain/release! ::b)))))

(deftest ^:parallel release-removes-entry-test
  (testing "release! removes the key so retained returns nil afterward"
    (retain/retain! ::c :ptr-c :manager)
    (retain/release! ::c)
    (is (nil? (retain/retained ::c)))))

(deftest ^:parallel double-release-is-noop-test
  (testing "release! on an absent key returns nil without throwing"
    (is (nil? (retain/release! ::never-retained)))))

;; ── release-kind! ───────────────────────────────────────────────────────────

(deftest ^:parallel release-kind-selectivity-test
  (testing "release-kind! removes only entries of the specified kind"
    (retain/retain! ::d1 :ptr-d1 :delegate)
    (retain/retain! ::d2 :ptr-d2 :delegate)
    (retain/retain! ::m1 :ptr-m1 :manager)
    (let [released (set (retain/release-kind! :delegate))]
      (is (= #{:ptr-d1 :ptr-d2} released))
      (is (nil? (retain/retained ::d1)))
      (is (nil? (retain/retained ::d2)))
      (is (= :ptr-m1 (retain/retained ::m1))))))

(deftest ^:parallel release-kind-empty-test
  (testing "release-kind! on an absent kind returns an empty vector"
    (is (= [] (retain/release-kind! :nonexistent-kind)))))

;; ── introspection ───────────────────────────────────────────────────────────

(deftest ^:parallel retained-keys-test
  (testing "retained-keys returns the set of all registered keys"
    (retain/retain! ::e :ptr-e :observer)
    (retain/retain! ::f :ptr-f :delegate)
    (is (= #{::e ::f} (retain/retained-keys)))))

(deftest ^:parallel retained-count-test
  (testing "retained-count reflects current registry size"
    (is (zero? (retain/retained-count)))
    (retain/retain! ::g :ptr-g :manager)
    (is (= 1 (retain/retained-count)))
    (retain/release! ::g)
    (is (zero? (retain/retained-count)))))

;; ── reset-all! ──────────────────────────────────────────────────────────────

(deftest ^:parallel reset-all-test
  (testing "reset-all! clears every entry"
    (retain/retain! ::h :ptr-h :delegate)
    (retain/retain! ::i :ptr-i :manager)
    (retain/reset-all!)
    (is (zero? (retain/retained-count)))
    (is (empty? (retain/retained-keys)))))
