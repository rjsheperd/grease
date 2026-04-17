;; tests/test_engine_nav.clj — on-device tests for grease.ios.nav (Phase 9)
;;
;; Verifies UINavigationController-backed navigation: registry management,
;; make-nav!, push!, view-controllers, set-title!, and present-modal!.
;;
;; All tests that touch UIKit must be called on the main thread.
;; Registry mutations in make-vc! are synchronous so count assertions
;; do not require a main-thread barrier.
;;
;; Prerequisites: App running, nREPL on port 23456.
;; Usage: clj -M tests/test_engine_nav.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. Namespace loads ──────────────────────────────────────────────────────

(check "1. grease.ios.nav namespace is accessible"
  "(do (require '[grease.ios.nav :as nav]) :ok)"
  #(= ":ok" (:value %)))

;; ─── 2. nav-vc-registry is an atom ───────────────────────────────────────────

(check "2. nav-vc-registry is an atom"
  "(do
     (require '[grease.ios.nav :as nav])
     (map? @nav/nav-vc-registry))"
  #(= "true" (:value %)))

;; ─── Setup: define reusable screen specs ─────────────────────────────────────

(eval!
  "(do
     (require '[grease.ios.screen :as screen]
              '[grease.ios.nav    :as nav])
     (screen/defscreen NavTestRoot
       :state {:n 0}
       :view  (fn [state] [:label {:text (str \"nav root #\" (:n state))}]))
     (screen/defscreen NavTestDetail
       :state {:n 1}
       :view  (fn [state] [:label {:text (str \"nav detail #\" (:n state))}]))
     :ok)"
  {})

;; ─── 3. make-nav! grows nav-vc-registry by 1 ─────────────────────────────────
;;
;; make-vc! registers the root VC synchronously before UIKit wires it up.
;; make-nav! calls initWithRootViewController: which accesses UIKit layout,
;; so it must run on the main thread.

(check "3. make-nav! adds 1 entry to nav-vc-registry"
  "(do
     (require '[grease.ios.nav  :as nav]
              '[grease.ios.repl :refer [on-main]])
     (def nav-before (count @nav/nav-vc-registry))
     (def nav-test-nc (on-main (nav/make-nav! NavTestRoot)))
     (= (inc nav-before) (count @nav/nav-vc-registry)))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 4. make-nav! returns a non-nil UINavigationController pointer ───────────

(check "4. make-nav! returns non-nil pointer"
  "(some? nav-test-nc)"
  #(= "true" (:value %)))

;; ─── 5. push! grows nav-vc-registry by 1 more ───────────────────────────────

(check "5. push! adds another entry to nav-vc-registry"
  "(do
     (require '[grease.ios.nav :as nav])
     (def nav-before-push (count @nav/nav-vc-registry))
     (nav/push! nav-test-nc NavTestDetail)
     (= (inc nav-before-push) (count @nav/nav-vc-registry)))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 6. view-controllers returns the full VC stack ───────────────────────────
;;
;; push! dispatches pushViewController:animated: asynchronously. An on-main
;; barrier ensures UIKit has processed it before we query the stack.

(check "6. view-controllers returns 2 VCs after make-nav! + push!"
  "(do
     (require '[grease.ios.nav  :as nav]
              '[grease.ios.repl :refer [on-main]])
     ;; barrier: wait for the push dispatch
     (on-main nil)
     (= 2 (count (on-main (nav/view-controllers nav-test-nc)))))"
  #(= "true" (:value %))
  :timeout 15000)

;; ─── 7. set-title! dispatches without crashing ───────────────────────────────

(check "7. set-title! dispatches to main thread without throwing"
  "(do
     (require '[grease.ios.nav  :as nav]
              '[grease.ios.repl :refer [on-main]])
     (let [vcs (on-main (nav/view-controllers nav-test-nc))]
       (nav/set-title! (first vcs) \"Nav Smoke\")
       (on-main nil)
       :ok))"
  #(= ":ok" (:value %))
  :timeout 10000)

;; ─── 8. present-modal! returns a UINavigationController and grows registry ───

(check "8. present-modal! from root VC grows registry and returns non-nil"
  "(do
     (require '[grease.ios.nav    :as nav]
              '[grease.ios.objc   :as objc-rt]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     (def nav-modal-before (count @nav/nav-vc-registry))
     (def nav-modal-nc
       (on-main
         (let [rvc (objc-rt/msg-send :pointer (ui/key-window) \"rootViewController\")]
           (nav/present-modal! rvc NavTestRoot))))
     (and (some? nav-modal-nc)
          (> (count @nav/nav-vc-registry) nav-modal-before)))"
  #(= "true" (:value %))
  :timeout 15000)

;; Dismiss the modal so it doesn't linger on screen.
(eval!
  "(do
     (require '[grease.ios.nav    :as nav]
              '[grease.ios.objc   :as objc-rt]
              '[grease.ios.uikit  :as ui]
              '[grease.ios.repl   :refer [on-main]])
     (on-main
       (let [rvc (objc-rt/msg-send :pointer (ui/key-window) \"rootViewController\")]
         (nav/dismiss-modal! rvc))))"
  {})

(run-suite "grease.ios.nav — Phase 9 UINavigationController")
