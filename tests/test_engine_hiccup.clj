;; tests/test_engine_hiccup.clj — on-device tests for the hiccup idiom layer
;;   (Phase 4.3: smart reconciler + reactive mount, Phase 6.3: constraints)
;;
;; Verifies mount!/unmount! lifecycle, reactive re-renders, and the
;; :key/:constraints path — all via observable UIKit side effects rather
;; than internal atom inspection, so the tests don't depend on private state.
;;
;; Prerequisites: App running, nREPL on port 23456.
;; Usage: clj -M tests/test_engine_hiccup.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. Namespace loads ──────────────────────────────────────────────────────

(check "1. grease.ios.hiccup namespace is accessible"
  "(do (require '[grease.ios.hiccup :as hiccup]) :ok)"
  #(= ":ok" (:value %)))

;; ─── 2. create-element ───────────────────────────────────────────────────────

(check "2. create-element :label returns non-nil pointer"
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.repl   :refer [on-main]])
     (on-main (some? (hiccup/create-element :label {:text \"test\"}))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 3. mount! adds a subview to the canvas ──────────────────────────────────
;;
;; mount! dispatches render! to the main queue asynchronously. We call a
;; second on-main after mount! to act as a FIFO barrier: by the time the
;; second on-main completes, the render dispatch ahead of it has already run.

(check "3. mount! renders a root node into the canvas (subview count = 1)"
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     ;; Create a detached canvas view on main thread.
     (def hiccup-test-canvas (on-main (hiccup/create-element :view {})))
     ;; mount! schedules render to main queue (async).
     (hiccup/mount! ::hiccup-smoke hiccup-test-canvas []
                    (fn [] [:label {:text \"mounted\"}]))
     ;; Barrier: this on-main is queued AFTER the render dispatch.
     (on-main nil)
     ;; The render has now run; canvas should have exactly 1 subview.
     (on-main (count (ui/subviews hiccup-test-canvas))))"
  #(= "1" (:value %))
  :timeout 15000)

;; ─── 4. unmount! removes the rendered subtree ────────────────────────────────

(check "4. unmount! removes the rendered subview (subview count = 0)"
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     ;; unmount! is synchronous for watch removal and teardown-rendered!,
     ;; but teardown-rendered! calls removeFromSuperview which needs main thread.
     (on-main (hiccup/unmount! ::hiccup-smoke))
     (on-main (count (ui/subviews hiccup-test-canvas))))"
  #(= "0" (:value %))
  :timeout 10000)

;; ─── 5. Reactive re-render via state atom ────────────────────────────────────
;;
;; Mount a view driven by a state atom. Swap! the atom → the watch fires
;; dispatch-main-async → re-render runs. A second on-main barrier confirms
;; the re-render completed without crashing (canvas still has its subview).

(check "5. reactive: state atom swap! triggers re-render without crash"
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     (def hiccup-state-atom (atom {:n 0}))
     (def hiccup-reactive-canvas (on-main (hiccup/create-element :view {})))
     (hiccup/mount! ::hiccup-reactive hiccup-reactive-canvas [hiccup-state-atom]
                    (fn [] [:label {:text (str \"count: \" (:n @hiccup-state-atom))}]))
     ;; barrier: wait for initial render
     (on-main nil)
     ;; trigger reactive re-render
     (swap! hiccup-state-atom update :n inc)
     ;; barrier: wait for re-render
     (on-main nil)
     ;; subview should still be present after re-render
     (on-main (= 1 (count (ui/subviews hiccup-reactive-canvas)))))"
  #(= "true" (:value %))
  :timeout 15000)

;; Cleanup reactive test resources.
(eval! "(on-main (hiccup/unmount! ::hiccup-reactive))" {})

;; ─── 6. Reconciler: re-render with same tag patches props ────────────────────
;;
;; Mount a label, then call render! directly with a new text prop.
;; The reconciler should patch the existing node rather than replace it.
;; Observable: canvas still has exactly 1 subview after the second render.

(check "6. reconciler: second render keeps subview count at 1"
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     (def hiccup-reconcile-canvas (on-main (hiccup/create-element :view {})))
     (on-main
       (hiccup/render! ::hiccup-reconcile hiccup-reconcile-canvas
                       [:label {:text \"first\"}])
       (hiccup/render! ::hiccup-reconcile hiccup-reconcile-canvas
                       [:label {:text \"second\"}])
       (count (ui/subviews hiccup-reconcile-canvas))))"
  #(= "1" (:value %))
  :timeout 10000)

;; Cleanup.
(eval! "(on-main (hiccup/teardown-rendered! ::hiccup-reconcile))" {})

;; ─── 7. :key / :constraints mount (Phase 6.3) ────────────────────────────────
;;
;; Verifies the full constraint path: constraint-key->view, parse-constraint-tuple,
;; disable-autoresizing!, and NSLayoutConstraint activation — all without crashing.
;; Observable: canvas has exactly 1 subview (the rendered container view).

(check "7. :key / :constraints mount completes without crash"
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     (def hiccup-constraint-canvas (on-main (hiccup/create-element :view {})))
     (hiccup/mount! ::hiccup-constraints hiccup-constraint-canvas []
                    (fn []
                      [:view {:constraints [[:eq :lbl :top    :parent :top    8.0]
                                            [:eq :lbl :left   :parent :left   8.0]
                                            [:eq :lbl :right  :parent :right -8.0]
                                            [:eq :lbl :bottom :parent :bottom -8.0]]}
                       [:label {:key :lbl :text \"constraint test\"}]]))
     (on-main nil)
     (on-main (= 1 (count (ui/subviews hiccup-constraint-canvas)))))"
  #(= "true" (:value %))
  :timeout 15000)

;; Cleanup.
(eval! "(on-main (hiccup/unmount! ::hiccup-constraints))" {})

(run-suite "grease.ios.hiccup — Phase 4.3 reconciler + Phase 6.3 constraints")
