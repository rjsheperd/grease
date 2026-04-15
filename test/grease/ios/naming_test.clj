(ns grease.ios.naming-test
  "Gate tests for grease.ios.naming — auto-derived from :examples in naming.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.naming :as naming]))

(use-fixtures :once
  (fn [f]
    (naming/init!)
    (f)))

;; =============================================================================
;; EDN structure tests
;; =============================================================================

(deftest ^:parallel naming-edn-loads-test
  (testing "naming.edn is readable EDN with :rules key"
    (let [raw (edn/read-string (slurp (io/resource "grease/naming.edn")))]
      (is (map? raw))
      (is (vector? (:rules raw)))
      (is (seq (:rules raw))))))

(deftest ^:parallel naming-edn-has-examples-test
  (testing "every rule has a non-empty :examples map"
    (let [{:keys [rules]} (edn/read-string (slurp (io/resource "grease/naming.edn")))]
      (doseq [rule rules]
        (is (map? (:examples rule)) (str "Rule missing :examples: " rule))
        (is (seq (:examples rule))  (str "Rule has empty :examples: " rule))))))

;; =============================================================================
;; Auto-derived tests from :examples
;; =============================================================================

(deftest examples-round-trip-test
  (testing "every :examples pair passes ->clj"
    (let [{:keys [rules]} (edn/read-string (slurp (io/resource "grease/naming.edn")))]
      (doseq [{:keys [scope examples transform]} rules]
        (doseq [[objc-name expected] examples]
          (let [actual (naming/->clj scope objc-name)]
            (is (= expected actual)
                (str "transform=" transform " scope=" scope
                     " input=" objc-name
                     " expected=" expected " got=" actual))))))))

;; =============================================================================
;; Specific named-case tests for readable failure messages
;; =============================================================================

(deftest ^:parallel strip+question-test
  (testing ":strip+question removes is/has prefix and adds ?"
    (is (= "playing?"  (naming/->clj :method "isPlaying")))
    (is (= "hidden?"   (naming/->clj :method "isHidden")))
    (is (= "prefix?"   (naming/->clj :method "hasPrefix")))
    (is (= "changes?"  (naming/->clj :method "hasChanges")))))

(deftest ^:parallel wrap-set-bang-test
  (testing ":wrap-set-bang produces set-name! form"
    (is (= "set-volume!"   (naming/->clj :setter "volume")))
    (is (= "set-text!"     (naming/->clj :setter "text")))
    (is (= "set-delegate!" (naming/->clj :setter "delegate")))))

(deftest ^:parallel camel->kebab-test
  (testing ":camel->kebab converts method names"
    (is (= "start-running"  (naming/->clj :method "startRunning")))
    (is (= "stop-running"   (naming/->clj :method "stopRunning")))
    (is (= "add-object"     (naming/->clj :method "addObject:")))
    (is (= "object-at-index" (naming/->clj :method "objectAtIndex:")))
    (is (= "set-object-for-key" (naming/->clj :method "setObject:forKey:")))))

(deftest ^:parallel camel->kebab-keyword-test
  (testing ":camel->kebab-keyword produces keywords"
    (is (= :ready-to-play (naming/->clj :enum-val "readyToPlay")))
    (is (= :unknown       (naming/->clj :enum-val "unknown")))
    (is (= :powered-on    (naming/->clj :enum-val "poweredOn")))
    (is (= :powered-off   (naming/->clj :enum-val "poweredOff")))))

(deftest ^:parallel strip-framework-prefix-test
  (testing ":strip-framework-prefix removes known prefixes"
    (is (= "player"          (naming/->clj :class "AVPlayer")))
    (is (= "url"             (naming/->clj :class "NSURL")))
    (is (= "string"          (naming/->clj :class "NSString")))
    (is (= "central-manager" (naming/->clj :class "CBCentralManager")))
    (is (= "label"           (naming/->clj :class "UILabel")))))

;; =============================================================================
;; transform-spec smoke test
;; =============================================================================

(deftest ^:parallel transform-spec-test
  (testing "transform-spec annotates a minimal spec"
    (let [spec {:framework "Test"
                :classes [{:name "AVPlayer"
                           :init []
                           :methods [{:selector "play" :encoding "v@:" :args [] :return "void"}
                                     {:selector "isPlaying" :encoding "B@:" :args [] :return "BOOL"}]
                           :properties [{:name "volume" :encoding "f" :readonly false}]}]
                :enums [{:name "AVPlayerStatus" :encoding "q"
                         :values [{:name "readyToPlay" :raw 1}]}]
                :constants []}
          annotated (naming/transform-spec spec)]
      ;; Class gets ::naming/clj-name
      (is (= "player" (-> annotated :classes first ::naming/clj-name)))
      ;; Methods get ::naming/clj-name
      (let [methods (-> annotated :classes first :methods)]
        (is (= "play"      (-> methods first ::naming/clj-name)))
        (is (= "playing?"  (-> methods second ::naming/clj-name))))
      ;; Properties get ::naming/clj-name and ::naming/setter-name
      (let [prop (-> annotated :classes first :properties first)]
        (is (= "volume"      (::naming/clj-name prop)))
        (is (= "set-volume!" (::naming/setter-name prop))))
      ;; Enum values get ::naming/clj-name
      (is (= :ready-to-play
             (-> annotated :enums first :values first ::naming/clj-name))))))
