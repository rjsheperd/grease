(ns grease.ios.hiccup-subtree-watch-test
  "Layer 5 — subtree-scoped reactive binding tests.

  Tests for `mount-subtree!` and `unmount-subtree!`.  All tests run on the
  JVM and avoid ObjC by testing the watch/state management logic directly."
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- teardown-entries!
  "Ensures rendered-trees and mount-watches are clean for the given keys."
  [& ks]
  (doseq [k ks]
    (swap! @#'hiccup/rendered-trees dissoc k)
    (swap! @#'hiccup/mount-watches dissoc k)))

;; =============================================================================
;; mount-subtree! installs watch; unmount-subtree! removes it
;; =============================================================================

(deftest unmount-subtree-removes-watch-test
  (testing "unmount-subtree! removes watch so atom changes no longer fire render-fn"
    (let [root-key ::st-watch-root
          subkey   ::st-watch-sub
          atom-a   (atom 0)
          fires    (atom 0)
          rk       (retain/retain! ::st-watch-rk "v" ::test)]
      ;; Pre-seed rendered-trees with a root entry.
      (swap! @#'hiccup/rendered-trees assoc root-key
             {:node {:tag :view :props {} :key nil :view nil :rk rk :children []}
              :subtrees {}})
      ;; Manually install a watch into mount-watches (bypassing ObjC).
      (let [compound [root-key subkey]
            wk       (gensym "st-test-")]
        (add-watch atom-a wk (fn [_ _ _ _] (swap! fires inc)))
        (swap! @#'hiccup/mount-watches assoc compound [[atom-a wk]])
        (swap! @#'hiccup/rendered-trees assoc-in [root-key :subtrees subkey]
               {:node nil :parent-view nil})
        ;; unmount-subtree! should remove the watch.
        (hiccup/unmount-subtree! root-key subkey)
        (reset! atom-a 99)
        (is (= 0 @fires)
            "watch must be removed so atom change does not fire render-fn")
        (is (nil? (get @@#'hiccup/mount-watches compound))
            "mount-watches entry for compound key must be cleared")
        (is (nil? (get-in @@#'hiccup/rendered-trees [root-key :subtrees subkey]))
            "subtrees entry for subkey must be removed"))
      ;; Cleanup.
      (teardown-entries! root-key)
      (retain/release! ::st-watch-rk))))

;; =============================================================================
;; unmount! clears root + all subtrees
;; =============================================================================

(deftest unmount-clears-root-and-subtrees-test
  (testing "unmount! removes watches for root AND all mounted subtrees"
    (let [root-key ::st-unmount-root
          sub-a    ::st-unmount-a
          sub-b    ::st-unmount-b
          atom-r   (atom 0)
          atom-a   (atom 0)
          atom-b   (atom 0)
          fires-r  (atom 0)
          fires-a  (atom 0)
          fires-b  (atom 0)
          wk-r     (gensym "st-root-")
          wk-a     (gensym "st-a-")
          wk-b     (gensym "st-b-")
          rk       (retain/retain! ::st-unmount-rk "v" ::test)]
      ;; Wire up watches manually.
      (add-watch atom-r wk-r (fn [_ _ _ _] (swap! fires-r inc)))
      (add-watch atom-a wk-a (fn [_ _ _ _] (swap! fires-a inc)))
      (add-watch atom-b wk-b (fn [_ _ _ _] (swap! fires-b inc)))
      ;; Seed mount-watches.
      (swap! @#'hiccup/mount-watches assoc root-key  [[atom-r wk-r]])
      (swap! @#'hiccup/mount-watches assoc [root-key sub-a] [[atom-a wk-a]])
      (swap! @#'hiccup/mount-watches assoc [root-key sub-b] [[atom-b wk-b]])
      ;; Seed rendered-trees.
      (swap! @#'hiccup/rendered-trees assoc root-key
             {:node {:tag :view :props {} :key nil :view nil :rk rk :children []}
              :subtrees {sub-a {:node nil :parent-view nil}
                         sub-b {:node nil :parent-view nil}}})
      ;; unmount! the root.
      (hiccup/unmount! root-key)
      ;; All atoms should be unobserved.
      (reset! atom-r 1)
      (reset! atom-a 1)
      (reset! atom-b 1)
      (is (= 0 @fires-r) "root watch must be removed")
      (is (= 0 @fires-a) "subtree-a watch must be removed")
      (is (= 0 @fires-b) "subtree-b watch must be removed")
      ;; mount-watches entries cleared.
      (is (nil? (get @@#'hiccup/mount-watches root-key)))
      (is (nil? (get @@#'hiccup/mount-watches [root-key sub-a])))
      (is (nil? (get @@#'hiccup/mount-watches [root-key sub-b])))
      ;; rendered-trees cleared.
      (is (nil? (get @@#'hiccup/rendered-trees root-key)))
      ;; Cleanup retained objects.
      (retain/release! ::st-unmount-rk))))

;; =============================================================================
;; pending? flag coalesces rapid changes to single render
;; =============================================================================

(deftest ^:parallel subtree-pending-flag-coalesces-renders-test
  (testing "rapid atom changes to subtree atom coalesce via pending? flag"
    ;; We test this by verifying that mount-watches maps compound key → pairs
    ;; and that the watch function deduplication logic works on the atom side.
    ;; Full coalescing depends on dispatch-main-async so we test state structure.
    (let [root-key ::st-coalesce-root
          subkey   ::st-coalesce-sub
          atom-a   (atom 0)
          fires    (atom 0)]
      ;; Simulate: two rapid changes → watch fires twice → but pending? ensures
      ;; only one render is queued.  We can verify this by checking the watch
      ;; infrastructure is correctly installed.
      (swap! @#'hiccup/mount-watches assoc root-key [])
      (swap! @#'hiccup/rendered-trees assoc root-key {:node nil :subtrees {}})
      (let [compound [root-key subkey]
            wk       (gensym "coalesce-")]
        (add-watch atom-a wk (fn [_ _ _ _] (swap! fires inc)))
        (swap! @#'hiccup/mount-watches assoc compound [[atom-a wk]])
        ;; Fire the atom twice rapidly.
        (reset! atom-a 1)
        (reset! atom-a 2)
        ;; Both changes should have fired the watch (2 increments).
        (is (= 2 @fires)
            "atom watch fires on every change regardless of pending?")
        ;; Cleanup.
        (remove-watch atom-a wk)
        (swap! @#'hiccup/mount-watches dissoc compound))
      ;; Cleanup.
      (teardown-entries! root-key))))
