(ns grease.ios.notify-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.notify :as notify]))

;; ── Test isolation ────────────────────────────────────────────────────────────

(defn- clear-dispatch! [f]
  (reset! notify/dispatch-table {})
  (f)
  (reset! notify/dispatch-table {}))

(use-fixtures :each clear-dispatch!)

;; ── EDN registry ─────────────────────────────────────────────────────────────

(deftest ^:parallel registry-contains-common-keys-test
  (testing "notifications.edn contains expected lifecycle keys"
    (let [reg @@#'notify/notification-names]
      (doseq [k [:keyboard-will-show :keyboard-will-hide
                 :app-did-become-active :app-did-enter-background
                 :orientation-change]]
        (is (contains? reg k) (str k " missing from notifications.edn"))))))

(deftest ^:parallel registry-has-objc-names-test
  (testing "each registry entry has an :objc string"
    (doseq [[k entry] @@#'notify/notification-names]
      (is (string? (:objc entry)) (str k " :objc value should be a string")))))

(deftest ^:parallel unknown-key-throws-test
  (testing "resolve-objc-name throws for unknown keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown notification key"
                          (#'notify/resolve-objc-name :not-a-notification)))))

(deftest ^:parallel string-key-passthrough-test
  (testing "string notification keys are returned as-is"
    (is (= "MyCustomNotification"
           (#'notify/resolve-objc-name "MyCustomNotification")))))

;; ── Dispatch table: on / off ─────────────────────────────────────────────────

(defn- simulate-notification!
  "Directly invoke all callbacks registered for `objc-name` without ObjC."
  [objc-name]
  (doseq [cb (vals (get @notify/dispatch-table objc-name))]
    (cb {:name objc-name :notification nil})))

(deftest on-registers-callback-test
  (testing "on adds callback to the dispatch table"
    (with-redefs [notify/wire-observer! (fn [_])]
      (let [received (atom [])
            _h (notify/on "TestNotification"
                          (fn [e] (swap! received conj (:name e))))]
        (simulate-notification! "TestNotification")
        (is (= ["TestNotification"] @received))))))

(deftest off-removes-callback-test
  (testing "off removes callback from dispatch table"
    (with-redefs [notify/wire-observer! (fn [_])
                  notify/unwire-observer! (fn [_])]
      (let [received (atom [])
            h (notify/on "TestNotification"
                         (fn [_] (swap! received conj :fired)))]
        (notify/off h)
        (simulate-notification! "TestNotification")
        (is (= [] @received))))))

(deftest once-fires-once-test
  (testing "once callback is removed after first invocation"
    (with-redefs [notify/wire-observer! (fn [_])
                  notify/unwire-observer! (fn [_])]
      (let [count (atom 0)]
        (notify/once "TestNotification" (fn [_] (swap! count inc)))
        (simulate-notification! "TestNotification")
        (simulate-notification! "TestNotification")
        (is (= 1 @count) "callback fires exactly once")))))

(deftest multiple-subscribers-test
  (testing "multiple subscribers for the same notification all fire"
    (with-redefs [notify/wire-observer! (fn [_])
                  notify/unwire-observer! (fn [_])]
      (let [a (atom 0)
            b (atom 0)]
        (notify/on "TestNotification" (fn [_] (swap! a inc)))
        (notify/on "TestNotification" (fn [_] (swap! b inc)))
        (simulate-notification! "TestNotification")
        (is (= 1 @a))
        (is (= 1 @b))))))
