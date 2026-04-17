(ns grease.ios.screen-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.retain]
            [grease.ios.screen :as screen]))

;; ── Test isolation ────────────────────────────────────────────────────────────

(defn- clear-screens! [f]
  (reset! screen/active-screens {})
  (f)
  (reset! screen/active-screens {}))

(use-fixtures :each clear-screens!)

;; ── defscreen macroexpansion / structure (pure) ───────────────────────────────

(deftest ^:parallel defscreen-expands-to-def-test
  (testing "defscreen expands to a def form"
    (let [expanded (macroexpand-1 '(grease.ios.screen/defscreen MyScreen
                                     :state {:count 0}
                                     :view (fn [s] [:label {:text "hi"}])))]
      (is (= "def" (name (first expanded)))
          "expansion is a def")
      (is (= 'MyScreen (second expanded))
          "def binds MyScreen"))))

(screen/defscreen TestScreen
  :state   {:value 42}
  :view    (fn [_state] [:label {:text "test"}])
  :on-mount nil
  :on-unmount nil)

(deftest ^:parallel defscreen-produces-spec-map-test
  (testing "defscreen var holds a spec map with required keys"
    (is (map? TestScreen))
    (is (= 'TestScreen (:name TestScreen)))
    (is (= {:value 42} (:initial-state TestScreen)))
    (is (fn? (:view TestScreen)))))

;; ── Active screen registry (pure) ────────────────────────────────────────────

(deftest screen-state-returns-nil-when-not-presented-test
  (testing "screen-state returns nil for unpresented screen"
    (is (nil? (screen/screen-state 'NotPresented)))))

(deftest dismiss-noop-when-not-presented-test
  (testing "dismiss! is safe to call on unpresented screen"
    (is (nil? (screen/dismiss! 'NotPresented)))))

;; ── on-mount / on-unmount hooks (pure, no ObjC) ──────────────────────────────

(screen/defscreen HookScreen
  :state     {:x 1}
  :view      (fn [_state] [:label {:text "hook"}])
  :on-mount  nil
  :on-unmount (fn [{:keys [state]}]
                (swap! state assoc :unmounted true)))

(deftest unmount-hook-fires-on-dismiss-test
  (testing "on-unmount is called with context on dismiss!"
    ;; Manually inject a context so we can test dismiss! without ObjC
    (let [state (atom {:x 1})]
      (swap! screen/active-screens assoc
             'HookScreen {:name 'HookScreen
                          :state state
                          :on-unmount (:on-unmount HookScreen)})
      (with-redefs [grease.ios.retain/release-kind! (fn [_])]
        (screen/dismiss! 'HookScreen))
      (is (true? (:unmounted @state))
          "on-unmount updated state"))))
