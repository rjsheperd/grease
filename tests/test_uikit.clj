;; tests/test_uikit.clj — Integration test for grease.ios.uikit + Phase 4 live manipulation.
;;
;; Tests (basic UIKit helpers):
;;   1. key-window returns UIWindow pointer
;;   2. root-view returns UIView pointer
;;   3. get-frame returns {:x :y :w :h}
;;   4. set-frame! round-trip on a fresh UIView
;;   5. describe-view has :class and :frame keys
;;   6. new-label + add-subview! increases subview count
;;
;; Tests (Phase 4 live manipulation):
;;   7. set-text-color!
;;   8. set-background-color!
;;   9. set-font-size!
;;  10. set-text-alignment!
;;  11. view-tree returns {:class :children}
;;  12. reactive atom drives UILabel text update
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_uikit.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── Basic UIKit helpers ────────────────────────────────────────────────────

(check "1. key-window returns UIWindow pointer"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (some? (on-main (ui/key-window))))"
  #(= "true" (:value %)))

(check "2. root-view returns UIView pointer"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (some? (on-main (ui/root-view))))"
  #(= "true" (:value %)))

(check "3. get-frame returns map with :x and :w keys"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (let [f (on-main (ui/get-frame (ui/root-view)))]
       (and (contains? f :x) (contains? f :w))))"
  #(= "true" (:value %)))

(check "4. set-frame! round-trip on fresh UIView"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.objc :as objc-rt])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [v (objc-rt/msg-send :pointer
                 (com.phronemophobic.grease/get-objc-class \"UIView\") \"new\")]
         (ui/set-frame! v 10.0 20.0 100.0 50.0)
         (select-keys (ui/get-frame v) [:x :w]))))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "10"))))

(check "5. describe-view has :class and :frame"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (let [d (on-main (ui/describe-view (ui/root-view)))]
       (and (contains? d :class) (contains? d :frame))))"
  #(= "true" (:value %)))

(check "6. new-label + add-subview! increases subview count"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [rv  (ui/root-view)
             n0  (count (ui/subviews rv))
             lbl (ui/new-label \"REPL test label\")]
         (ui/set-frame! lbl 20.0 100.0 280.0 44.0)
         (ui/add-subview! rv lbl)
         (> (count (ui/subviews rv)) n0))))"
  #(= "true" (:value %)))

;; ─── Phase 4: setup shared test label ──────────────────────────────────────

(eval!
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (def test-label
       (on-main
         (let [lbl (ui/new-label \"Phase 4 Test\")]
           (ui/set-frame! lbl 20.0 200.0 280.0 60.0)
           (ui/add-subview! (ui/root-view) lbl)
           lbl)))
     :label-created)"
  {:timeout 10000})

(check "7. set-text-color! (red) no crash"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (ui/set-text-color! test-label 1.0 0.0 0.0 1.0))
     :ok)"
  #(and (:ok? %) (= ":ok" (:value %))))

(check "8. set-background-color! (yellow) no crash"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (ui/set-background-color! test-label 1.0 1.0 0.0 0.8))
     :ok)"
  #(and (:ok? %) (= ":ok" (:value %))))

(check "9. set-font-size! (24pt) no crash"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (ui/set-font-size! test-label 24.0))
     :ok)"
  #(and (:ok? %) (= ":ok" (:value %))))

(check "10. set-text-alignment! (center) no crash"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main (ui/set-text-alignment! test-label 1))
     :ok)"
  #(and (:ok? %) (= ":ok" (:value %))))

(check "11. view-tree returns map with :class and :children"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (let [t (on-main (ui/view-tree (ui/root-view) 1))]
       (and (contains? t :class) (contains? t :children))))"
  #(= "true" (:value %))
  :timeout 15000)

(check "12. reactive atom drives UILabel text update"
  "(do
     (require '[grease.ios.uikit :as ui])
     (require '[grease.ios.repl :refer [on-main]])
     (require '[grease.ios.foundation :as f])
     (def display-text (atom \"initial\"))
     (add-watch display-text :ui-sync
       (fn [_ _ _ new-val]
         (com.phronemophobic.grease/dispatch-main-async
           (fn []
             (grease.ios.objc/msg-send :void test-label
               \"setText:\" :pointer (f/->nsstring new-val))))))
     (reset! display-text \"Live from REPL!\")
     (on-main
       (f/nsstring->str
         (grease.ios.objc/msg-send :pointer test-label \"text\"))))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "Live from REPL!")))
  :timeout 10000)

(run-suite "grease.ios.uikit")
