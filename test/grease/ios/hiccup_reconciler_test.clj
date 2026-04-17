(ns grease.ios.hiccup-reconciler-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.retain :as retain]))

;; Tests for the pure-Clojure parts of the Phase 4.3 smart reconciler.
;;
;; ObjC-dependent paths (render-tree!, reconcile! with live view pointers,
;; mount! dispatch-main-async) are exercised by the on-device smoke test
;; (dev/test_idiom_layer.clj).  These unit tests cover the retain-registry
;; and watch-management logic that can run on the JVM.

;; ── release-rendered! ─────────────────────────────────────────────────────────

(deftest ^:parallel release-rendered-nil-test
  (testing "release-rendered! is a no-op on nil"
    (is (nil? (#'hiccup/release-rendered! nil)))))

(deftest ^:parallel release-rendered-leaf-test
  (testing "release-rendered! releases a single leaf node's retain key"
    (let [rk   (retain/retain! ::rr-leaf "leaf-val" ::test)
          node {:tag :label :props {} :view nil :rk rk :children []}]
      (#'hiccup/release-rendered! node)
      (is (nil? (retain/retained rk))))))

(deftest ^:parallel release-rendered-tree-test
  (testing "release-rendered! recursively releases parent and child retain keys"
    (let [rk-parent (retain/retain! ::rr-parent "pv" ::test)
          rk-child  (retain/retain! ::rr-child  "cv" ::test)
          node      {:tag :view :props {} :view nil :rk rk-parent
                     :children [{:tag :label :props {} :view nil
                                 :rk rk-child :children []}]}]
      (#'hiccup/release-rendered! node)
      (is (nil? (retain/retained rk-parent)))
      (is (nil? (retain/retained rk-child))))))

(deftest ^:parallel release-rendered-deep-tree-test
  (testing "release-rendered! releases all nodes in a three-level subtree"
    (let [rk-a (retain/retain! ::rr-a "a" ::test)
          rk-b (retain/retain! ::rr-b "b" ::test)
          rk-c (retain/retain! ::rr-c "c" ::test)
          node {:tag :stack :props {} :view nil :rk rk-a
                :children [{:tag :view :props {} :view nil :rk rk-b
                            :children [{:tag :label :props {} :view nil
                                        :rk rk-c :children []}]}]}]
      (#'hiccup/release-rendered! node)
      (is (nil? (retain/retained rk-a)))
      (is (nil? (retain/retained rk-b)))
      (is (nil? (retain/retained rk-c))))))

;; ── unmount! — watch removal ───────────────────────────────────────────────────

(deftest ^:parallel unmount-removes-atom-watch-test
  (testing "unmount! removes the watch registered in mount-watches for a root-key"
    (let [a        (atom 0)
          fires    (atom 0)
          root-key ::unmount-single-test
          wk       (gensym "mount-test-")]
      ;; Manually wire up state the same way mount! would
      (add-watch a wk (fn [_ _ _ _] (swap! fires inc)))
      (swap! @#'hiccup/mount-watches assoc root-key [[a wk]])
      ;; unmount! (no rendered-tree entry, so teardown-rendered! is a no-op)
      (hiccup/unmount! root-key)
      ;; Atom change must not fire the removed watch
      (reset! a 99)
      (is (= 0 @fires))
      (is (nil? (get @@#'hiccup/mount-watches root-key))))))

(deftest ^:parallel unmount-removes-multiple-atom-watches-test
  (testing "unmount! removes watches from all source atoms"
    (let [a1       (atom 1)
          a2       (atom 2)
          fires    (atom 0)
          root-key ::unmount-multi-test
          wk1      (gensym "mount-test-")
          wk2      (gensym "mount-test-")]
      (add-watch a1 wk1 (fn [_ _ _ _] (swap! fires inc)))
      (add-watch a2 wk2 (fn [_ _ _ _] (swap! fires inc)))
      (swap! @#'hiccup/mount-watches assoc root-key [[a1 wk1] [a2 wk2]])
      (hiccup/unmount! root-key)
      (reset! a1 10)
      (reset! a2 20)
      (is (= 0 @fires)))))

(deftest ^:parallel unmount-noop-when-not-mounted-test
  (testing "unmount! does not throw when the root-key has no registered watches"
    (is (nil? (hiccup/unmount! ::never-mounted-key)))))

;; ── rendered-trees state ──────────────────────────────────────────────────────

(deftest ^:parallel rendered-trees-entry-cleared-after-release-test
  (testing "releasing a rendered-tree entry removes it from rendered-trees"
    (let [rk       (retain/retain! ::rt-entry "v" ::test)
          root-key ::rt-clear-test
          node     {:tag :label :props {} :view nil :rk rk :children []}]
      ;; Inject a fake entry directly
      (swap! @#'hiccup/rendered-trees assoc root-key node)
      (is (some? (get @@#'hiccup/rendered-trees root-key)))
      ;; Manually clean up (mirrors what teardown-rendered! does after ObjC calls)
      (#'hiccup/release-rendered! node)
      (swap! @#'hiccup/rendered-trees dissoc root-key)
      (is (nil? (get @@#'hiccup/rendered-trees root-key)))
      (is (nil? (retain/retained rk))))))
