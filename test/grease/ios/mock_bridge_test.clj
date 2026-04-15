(ns grease.ios.mock-bridge-test
  "Gate test for mock bridge — verifies the recording machinery works
  before any engine code is added."
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.mock-bridge :as mock]))

;; =============================================================================
;; Minimal stubs so with-redefs has vars to target.
;; When the real engine namespaces are loaded, these stubs are unnecessary.
;; =============================================================================

;; Stub out the namespaces that with-mock redefs.
;; On JVM dev machine these may fail to load (GraalVM native deps).
;; The stubs below allow the test to verify mock mechanics in isolation.

(def ^:dynamic objc-msg-send nil)
(def ^:dynamic grease-get-objc-class nil)
(def ^:dynamic grease-objc-new nil)

;; =============================================================================
;; Gate tests
;; =============================================================================

(deftest ^:parallel mock-records-calls-test
  (testing "with-mock records msg-send calls"
    ;; Directly exercise the mock functions (bypass with-redefs to test
    ;; the recording atoms themselves)
    (mock/clear!)
    (#'grease.ios.mock-bridge/mock-msg-send :void {:class-name "AVPlayer"} "play")
    (let [cs (mock/calls)]
      (is (= 1 (count cs)))
      (is (= :void    (:ret-type (first cs))))
      (is (= "play"   (:sel (first cs))))
      (is (= {:class-name "AVPlayer"} (:obj (first cs)))))))

(deftest ^:parallel mock-get-class-test
  (testing "mock-get-class returns a synthetic class map"
    (let [cls (#'grease.ios.mock-bridge/mock-get-class "AVPlayer")]
      (is (= {:class-name "AVPlayer"} cls)))))

(deftest ^:parallel mock-objc-new-test
  (testing "mock-objc-new returns a synthetic instance map"
    (let [inst (#'grease.ios.mock-bridge/mock-objc-new {:class-name "AVPlayer"})]
      (is (= {:instance-of {:class-name "AVPlayer"}} inst)))))

(deftest ^:parallel canned-responses-test
  (testing "set-response! and responses returned by mock-msg-send"
    (mock/clear!)
    (mock/set-response! "status" 1)
    (let [result (#'grease.ios.mock-bridge/mock-msg-send :int64
                                                         {:class-name "AVPlayer"}
                                                         "status")]
      (is (= 1 result)))))

(deftest ^:parallel clear-resets-state-test
  (testing "clear! resets both call log and response map"
    (#'grease.ios.mock-bridge/mock-msg-send :void {:class-name "X"} "foo")
    (mock/set-response! "bar" 42)
    (mock/clear!)
    (is (empty? (mock/calls)))
    (is (nil? (#'grease.ios.mock-bridge/mock-msg-send :int64 {} "bar")))))

(deftest ^:parallel with-responses-test
  (testing "with-responses pre-loads canned selector map"
    (mock/clear!)
    (mock/set-response! "length" 99)
    (is (= 99 (#'grease.ios.mock-bridge/mock-msg-send :int64 {} "length")))))

(deftest ^:parallel assert-called-test
  (testing "assert-called finds matching selector in log"
    (mock/clear!)
    (#'grease.ios.mock-bridge/mock-msg-send :void {} "play")
    (#'grease.ios.mock-bridge/mock-msg-send :void {} "pause")
    (let [matches (mock/assert-called "play")]
      (is (= 1 (count matches))))))

(deftest ^:parallel assert-not-called-test
  (testing "assert-not-called passes when selector absent"
    (mock/clear!)
    (#'grease.ios.mock-bridge/mock-msg-send :void {} "play")
    (mock/assert-not-called "stop")))

(deftest ^:parallel assert-call-count-test
  (testing "assert-call-count verifies exact invocation count"
    (mock/clear!)
    (#'grease.ios.mock-bridge/mock-msg-send :void {} "tick")
    (#'grease.ios.mock-bridge/mock-msg-send :void {} "tick")
    (#'grease.ios.mock-bridge/mock-msg-send :void {} "tick")
    (mock/assert-call-count "tick" 3)))
