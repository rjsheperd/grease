(ns grease.scrape.apple-docs-test
  "Tests for grease.scrape.apple-docs — Phase 6.1 gate.

  All tests run against fixture JSON under test/fixtures/apple-docs/.
  No network access required."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.scrape.apple-docs :as apple-docs]))

;; =============================================================================
;; Fixture binding — point cache-dir at pre-populated test fixtures
;; =============================================================================

(def ^:private fixture-dir "test/fixtures/apple-docs")

(use-fixtures :each
  (fn [f]
    (binding [apple-docs/*cache-dir* fixture-dir]
      (f))))

;; =============================================================================
;; fetch — reads from cache (fixture files)
;; =============================================================================

(deftest ^:parallel fetch-framework-test
  (testing "fetch parses corelocation.json fixture"
    (let [doc (apple-docs/fetch "corelocation")]
      (is (map? doc))
      (is (= "Core Location" (get-in doc [:metadata :title]))))))

(deftest ^:parallel fetch-class-test
  (testing "fetch parses corelocation/cllocationmanager.json fixture"
    (let [doc (apple-docs/fetch "corelocation/cllocationmanager")]
      (is (map? doc))
      (is (= "CLLocationManager" (get-in doc [:metadata :title])))
      (is (= "cl" (get-in doc [:metadata :symbolKind]))))))

(deftest ^:parallel fetch-returns-keyword-keys-test
  (testing "fetch returns maps with keyword keys"
    (let [doc (apple-docs/fetch "corelocation")]
      (is (contains? doc :metadata))
      (is (contains? doc :references))
      (is (contains? doc :topicSections)))))

(deftest ^:parallel fetch-missing-caches-test
  (testing "fetch returns nil / throws for unknown doc-path"
    ;; Redef fetch-remote so we don't hit the network; just simulate a 404 via exception
    (with-redefs [grease.scrape.apple-docs/fetch-remote
                  (fn [_url] (throw (java.io.IOException. "simulated 404")))]
      (is (thrown? Exception
                   (apple-docs/fetch "corelocation/nonexistentclass"))))))

;; =============================================================================
;; framework-symbols — filters to role=symbol
;; =============================================================================

(deftest ^:parallel framework-symbols-returns-symbols-only-test
  (testing "framework-symbols returns only role=symbol references"
    (let [syms (apple-docs/framework-symbols "corelocation")]
      (is (seq syms))
      ;; all returned entries must have role=symbol
      (is (every? #(= "symbol" (:role %)) syms)))))

(deftest ^:parallel framework-symbols-count-test
  (testing "fixture has exactly 2 symbol references (CLLocationManager + CLLocation)"
    (let [syms (apple-docs/framework-symbols "corelocation")]
      ;; corelocation.json has 2 symbol refs + 1 collection ref
      (is (= 2 (count syms))))))

(deftest ^:parallel framework-symbols-case-insensitive-test
  (testing "framework-symbols accepts mixed-case framework name"
    (let [syms (apple-docs/framework-symbols "CoreLocation")]
      (is (= 2 (count syms))))))

(deftest ^:parallel framework-symbols-titles-test
  (testing "framework-symbols includes CLLocationManager and CLLocation"
    (let [titles (set (map :title (apple-docs/framework-symbols "corelocation")))]
      (is (contains? titles "CLLocationManager"))
      (is (contains? titles "CLLocation")))))

;; =============================================================================
;; class-identifiers
;; =============================================================================

(deftest ^:parallel class-identifiers-no-colons-test
  (testing "class-identifiers excludes references whose title contains a colon"
    (let [ids (apple-docs/class-identifiers "corelocation")]
      ;; All class names in our fixture have no colon in their title
      (is (every? #(not (.contains % ":")) ids)))))

;; =============================================================================
;; class-doc
;; =============================================================================

(deftest ^:parallel class-doc-returns-map-test
  (testing "class-doc returns parsed JSON map for known class"
    (let [doc (apple-docs/class-doc "CoreLocation" "CLLocationManager")]
      (is (map? doc))
      (is (= "CLLocationManager" (get-in doc [:metadata :title]))))))

(deftest ^:parallel class-doc-nil-on-missing-test
  (testing "class-doc returns nil for unknown class"
    (with-redefs [grease.scrape.apple-docs/fetch-remote
                  (fn [_url] (throw (java.io.IOException. "simulated 404")))]
      (is (nil? (apple-docs/class-doc "CoreLocation" "NonExistent"))))))

;; =============================================================================
;; class-methods
;; =============================================================================

(deftest ^:parallel class-methods-count-test
  (testing "class-methods returns 4 symbol refs from CLLocationManager fixture"
    ;; Fixture has: requestWhenInUseAuthorization, startUpdatingLocation,
    ;;              stopUpdatingLocation, CLLocationManager (self-ref) — all role=symbol
    (let [methods (apple-docs/class-methods "CoreLocation" "CLLocationManager")]
      (is (= 4 (count methods))))))

(deftest ^:parallel class-methods-titles-test
  (testing "class-methods includes known method titles"
    (let [titles (set (map :title (apple-docs/class-methods "CoreLocation" "CLLocationManager")))]
      (is (contains? titles "startUpdatingLocation"))
      (is (contains? titles "requestWhenInUseAuthorization"))
      (is (contains? titles "stopUpdatingLocation")))))

;; =============================================================================
;; fragment-text
;; =============================================================================

(deftest ^:parallel fragment-text-method-test
  (testing "fragment-text reconstructs ObjC instance method declaration"
    (let [ref {:fragments [{:kind "text"       :text "- "}
                           {:kind "identifier" :text "startUpdatingLocation"}]}]
      (is (= "- startUpdatingLocation" (apple-docs/fragment-text ref))))))

(deftest ^:parallel fragment-text-class-test
  (testing "fragment-text reconstructs @interface declaration for a class"
    (let [ref {:fragments [{:kind "keyword"    :text "@interface"}
                           {:kind "text"       :text " "}
                           {:kind "identifier" :text "CLLocationManager"}]}]
      (is (= "@interface CLLocationManager" (apple-docs/fragment-text ref))))))

(deftest ^:parallel fragment-text-empty-test
  (testing "fragment-text returns empty string for no fragments"
    (is (= "" (apple-docs/fragment-text {:fragments []})))))
