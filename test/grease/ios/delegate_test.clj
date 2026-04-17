(ns grease.ios.delegate-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.delegate :as delegate]))

;; ── Selector reconstruction (pure) ──────────────────────────────────────────

(deftest ^:parallel single-part-selector-test
  (testing "single keyword → camelCase with trailing colon"
    (is (= "didFinishLoading:"
           (delegate/method-descriptor->selector [:did-finish-loading nil])))))

(deftest ^:parallel multi-part-selector-test
  (testing "multi-keyword descriptor → joined camelCase selector"
    (is (= "locationManager:didUpdateLocations:"
           (delegate/method-descriptor->selector
            [:location-manager nil :did-update-locations nil])))))

(deftest ^:parallel selector-with-named-bindings-test
  (testing "binding symbols do not affect selector output"
    (is (= "locationManager:didFailWithError:"
           (delegate/method-descriptor->selector
            [:location-manager 'mgr :did-fail-with-error 'err])))))

(deftest ^:parallel zero-arg-selector-test
  (testing "empty descriptor returns empty string"
    (is (= "" (delegate/method-descriptor->selector [])))))

(deftest ^:parallel single-uppercase-segment-test
  (testing "single-word keyword becomes lowercase camelCase"
    (is (= "dealloc:" (delegate/method-descriptor->selector [:dealloc nil])))))

;; ── defdelegate macroexpansion (pure) ────────────────────────────────────────

(deftest ^:parallel defdelegate-expands-to-do-test
  (testing "defdelegate expands to a do form"
    (let [expanded (macroexpand-1
                    '(grease.ios.delegate/defdelegate
                       FakeDelegate "FakeProtocol"
                       {:state   {:count 0}
                        :methods {[:did-something _ :with-value v]
                                  (fn [st _ v] (update st :count inc))}}))]
      (is (= 'do (first expanded))
          "expansion starts with do"))))

(deftest ^:parallel defdelegate-defines-factory-test
  (testing "expansion contains a defn for the factory"
    (let [expanded (macroexpand-1
                    '(grease.ios.delegate/defdelegate
                       FakeDelegate "FakeProtocol"
                       {:state {} :methods {}}))
          forms    (rest expanded)]
      (is (some #(and (seq? %)
                      (= "defn" (name (first %)))
                      (= 'FakeDelegate (second %)))
                forms)
          "expansion includes defn FakeDelegate"))))

(deftest ^:parallel defdelegate-creates-cls-var-test
  (testing "expansion contains defonce for the class var"
    (let [expanded (macroexpand-1
                    '(grease.ios.delegate/defdelegate
                       FakeDelegate "FakeProtocol"
                       {:state {} :methods {}}))
          forms    (rest expanded)]
      (is (some #(and (seq? %)
                      (= "defonce" (name (first %)))
                      (= '__FakeDelegate__cls (second %)))
                forms)
          "expansion includes defonce __FakeDelegate__cls"))))
