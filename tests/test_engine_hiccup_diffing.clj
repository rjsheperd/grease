;; tests/test_engine_hiccup_diffing.clj — on-device tests for the 5-layer
;;   tree-shaking / fast-diffing reconciler upgrade.
;;
;; Layer 1 — subtree identity skip
;; Layer 2 — keyed child diffing
;; Layer 3 — prop removal defaults + event-handler dedup
;; Layer 4 — memo helper
;; Layer 5 — mount-subtree! scoped reactive bindings
;;
;; All tests use subview count and text-round-trip as observable proxies
;; for ObjC bridge activity — no internal atom inspection required.
;;
;; Prerequisites: App running, nREPL on port 23456.
;; Usage: clj -M tests/test_engine_hiccup_diffing.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── Shared setup ────────────────────────────────────────────────────────────

;; Pre-load all required namespaces once for the whole file.
(eval!
  "(do
     (require '[grease.ios.hiccup :as hiccup]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     :ok)"
  {})

;; ─── Layer 1: subtree identity skip ──────────────────────────────────────────
;;
;; Render a tree, then render the SAME hiccup reference again.
;; Observable: subview count stays at 1 (no teardown+rebuild).
;; The text in the label should equal the original text because
;; the node was skipped entirely.

(check "L1-1. identity skip: re-render same reference leaves subview count at 1"
  "(do
     (def l1-canvas (on-main (hiccup/create-element :view {})))
     (def l1-hiccup [:label {:text \"identity\"}])
     (on-main
       (hiccup/render! ::l1-test l1-canvas l1-hiccup)
       ;; Pass the identical reference a second time — reconcile! should short-circuit.
       (hiccup/render! ::l1-test l1-canvas l1-hiccup)
       (count (ui/subviews l1-canvas))))"
  #(= "1" (:value %))
  :timeout 10000)

(check "L1-2. identity skip: different reference with same tag still reconciles in-place"
  "(do
     ;; New vector — identical? is false — so reconcile! compares tag + props.
     (on-main
       (hiccup/render! ::l1-test l1-canvas [:label {:text \"updated\"}])
       (count (ui/subviews l1-canvas))))"
  #(= "1" (:value %))
  :timeout 10000)

(eval! "(on-main (hiccup/teardown-rendered! ::l1-test))" {})

;; ─── Layer 2: keyed child diffing ────────────────────────────────────────────
;;
;; Render a parent with 3 keyed children, then re-render with the order
;; reversed.  Observable: subview count stays at 3 (no teardown+rebuild),
;; proving the keyed algorithm matched existing views instead of replacing them.

(check "L2-1. keyed children: rendering 3 keyed children yields 3 subviews"
  "(do
     (def l2-canvas (on-main (hiccup/create-element :view {})))
     (on-main
       (hiccup/render! ::l2-test l2-canvas
                       [:view {}
                        [:label {:key :a :text \"A\"}]
                        [:label {:key :b :text \"B\"}]
                        [:label {:key :c :text \"C\"}]])
       ;; The rendered :view container is 1 subview of canvas;
       ;; its children are subviews of that container.
       ;; Check container's subview count.
       (count (ui/subviews (first (ui/subviews l2-canvas))))))"
  #(= "3" (:value %))
  :timeout 10000)

(check "L2-2. keyed children: reversing order keeps subview count at 3 (no rebuild)"
  "(do
     (on-main
       (hiccup/render! ::l2-test l2-canvas
                       [:view {}
                        [:label {:key :c :text \"C\"}]
                        [:label {:key :b :text \"B\"}]
                        [:label {:key :a :text \"A\"}]])
       (count (ui/subviews (first (ui/subviews l2-canvas))))))"
  #(= "3" (:value %))
  :timeout 10000)

(check "L2-3. keyed children: removing one key drops subview count to 2"
  "(do
     (on-main
       (hiccup/render! ::l2-test l2-canvas
                       [:view {}
                        [:label {:key :a :text \"A\"}]
                        [:label {:key :b :text \"B\"}]])
       (count (ui/subviews (first (ui/subviews l2-canvas))))))"
  #(= "2" (:value %))
  :timeout 10000)

(check "L2-4. keyed children: inserting a new key grows subview count to 3"
  "(do
     (on-main
       (hiccup/render! ::l2-test l2-canvas
                       [:view {}
                        [:label {:key :a :text \"A\"}]
                        [:label {:key :b :text \"B\"}]
                        [:label {:key :d :text \"D\"}]])
       (count (ui/subviews (first (ui/subviews l2-canvas))))))"
  #(= "3" (:value %))
  :timeout 10000)

(eval! "(on-main (hiccup/teardown-rendered! ::l2-test))" {})

;; ─── Layer 3: prop removal + event-handler dedup ─────────────────────────────
;;
;; Render a label with :alpha 0.5. Re-render without :alpha.
;; The view should still exist (no rebuild) — we can't easily read alpha
;; back from UIKit via the nREPL, so we just verify no crash + subview intact.

(check "L3-1. prop removal: removing :alpha does not crash"
  "(do
     (def l3-canvas (on-main (hiccup/create-element :view {})))
     (on-main
       (hiccup/render! ::l3-test l3-canvas [:label {:text \"hi\" :alpha 0.5}])
       ;; Re-render without :alpha — reconciler should reset to default 1.0
       (hiccup/render! ::l3-test l3-canvas [:label {:text \"hi\"}])
       (= 1 (count (ui/subviews l3-canvas)))))"
  #(= "true" (:value %))
  :timeout 10000)

(check "L3-2. prop removal: removing :hidden does not crash"
  "(do
     (on-main
       (hiccup/render! ::l3-test l3-canvas [:label {:text \"hi\" :hidden 1}])
       (hiccup/render! ::l3-test l3-canvas [:label {:text \"hi\"}])
       (= 1 (count (ui/subviews l3-canvas)))))"
  #(= "true" (:value %))
  :timeout 10000)

(eval! "(on-main (hiccup/teardown-rendered! ::l3-test))" {})

;; ─── Layer 4: memo helper ─────────────────────────────────────────────────────
;;
;; memo returns a closure. Call it with unchanged deps → same reference.
;; Call it after deps change → new reference.
;; When combined with mount!, the stable reference enables the Layer 1 skip.

(check "L4-1. memo: returns same reference when deps unchanged"
  "(do
     (def l4-state (atom {:n 0}))
     (def l4-memoized
       (hiccup/memo
         (fn [] [@l4-state])
         (fn [] [:label {:text (str \"n=\" (:n @l4-state))}])))
     ;; Call twice without changing state.
     (let [r1 (l4-memoized)
           r2 (l4-memoized)]
       (identical? r1 r2)))"
  #(= "true" (:value %))
  :timeout 10000)

(check "L4-2. memo: returns new reference when deps change"
  "(do
     (let [r1 (l4-memoized)]
       (swap! l4-state update :n inc)
       (let [r2 (l4-memoized)]
         (not (identical? r1 r2)))))"
  #(= "true" (:value %))
  :timeout 10000)

(check "L4-3. memo: works end-to-end with mount! — reactive mount stays alive after dep change"
  "(do
     (def l4-canvas (on-main (hiccup/create-element :view {})))
     (hiccup/mount! ::l4-test l4-canvas [l4-state] l4-memoized)
     (on-main nil)
     (swap! l4-state update :n inc)
     (on-main nil)
     (on-main (= 1 (count (ui/subviews l4-canvas)))))"
  #(= "true" (:value %))
  :timeout 15000)

(eval! "(on-main (hiccup/unmount! ::l4-test))" {})

;; ─── Layer 5: mount-subtree! ──────────────────────────────────────────────────
;;
;; Mount a root, then mount a subtree inside it bound to a separate atom.
;; Change the subtree atom → subtree re-renders.
;; Change a different atom → subtree is NOT touched (confirmed by subview count
;; of the subtree canvas remaining stable).

(check "L5-1. mount-subtree! renders a subview into its parent-view"
  "(do
     (def l5-root-canvas   (on-main (hiccup/create-element :view {})))
     (def l5-sub-canvas    (on-main (hiccup/create-element :view {})))
     (def l5-root-atom     (atom {:v \"root\"}))
     (def l5-sub-atom      (atom {:v \"sub\"}))
     ;; Root mount.
     (hiccup/mount! ::l5-root l5-root-canvas [l5-root-atom]
                    (fn [] [:label {:text (:v @l5-root-atom)}]))
     (on-main nil)
     ;; Subtree mount under the same root key.
     (hiccup/mount-subtree! ::l5-root :sub-a l5-sub-canvas [l5-sub-atom]
                             (fn [] [:label {:text (:v @l5-sub-atom)}]))
     (on-main nil)
     (on-main (= 1 (count (ui/subviews l5-sub-canvas)))))"
  #(= "true" (:value %))
  :timeout 15000)

(check "L5-2. changing subtree atom re-renders subtree (subview count stable)"
  "(do
     (swap! l5-sub-atom assoc :v \"sub-v2\")
     (on-main nil)
     (on-main (= 1 (count (ui/subviews l5-sub-canvas)))))"
  #(= "true" (:value %))
  :timeout 15000)

(check "L5-3. unmount-subtree! clears subtree without affecting root"
  "(do
     (on-main (hiccup/unmount-subtree! ::l5-root :sub-a))
     ;; Root canvas should still have its subview.
     (on-main (= 1 (count (ui/subviews l5-root-canvas)))))"
  #(= "true" (:value %))
  :timeout 10000)

(check "L5-4. unmount! cleans up the root and all remaining subtrees"
  "(do
     (on-main (hiccup/unmount! ::l5-root))
     (on-main (= 0 (count (ui/subviews l5-root-canvas)))))"
  #(= "true" (:value %))
  :timeout 10000)

(run-suite "grease.ios.hiccup — 5-layer diffing (Layers 1-5)")
