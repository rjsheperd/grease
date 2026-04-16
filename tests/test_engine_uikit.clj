;; tests/test_engine_uikit.clj — Phase 4.2 on-device UIKit via engine API.
;;
;; Validates the UIKit engine pipeline on real hardware.
;; All UIKit operations run on the main thread.
;;
;; Prerequisites:
;;   - App running on device (debug-demo-app --noinstall)
;;
;; Usage:
;;   clj -M tests/test_engine_uikit.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. UIKit spec loaded ────────────────────────────────────────────────────

(check "1. UIKit spec loaded in registry"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.registry :as registry])
     (some? (registry/class-spec \"UIApplication\")))"
  #(= "true" (:value %)))

;; ─── 2. UIApplication sharedApplication (class method) ──────────────────────

(check "2. UIApplication sharedApplication returns non-nil ObjcObject"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (some? (api/make \"UIApplication\" \"sharedApplication\"))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 3. UILabel instantiation ────────────────────────────────────────────────

(check "3. UILabel new returns ObjcObject"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (some? (api/make \"UILabel\" \"new\"))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 4. UILabel setText: + text round-trip ───────────────────────────────────

(check "4. UILabel text round-trip via setText: and text"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [label (api/make \"UILabel\" \"new\")]
         (api/call label \"setText:\" \"hello engine\")
         (api/call label \"text\"))))"
  #(= "\"hello engine\"" (:value %))
  :timeout 10000)

;; ─── 5. UIApplication keyWindow non-nil ─────────────────────────────────────

(check "5. keyWindow returns a non-nil UIWindow"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [app (api/make \"UIApplication\" \"sharedApplication\")]
         (some? (api/call app \"keyWindow\")))))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 6. rootViewController accessible ────────────────────────────────────────

(check "6. rootViewController non-nil via keyWindow chain (using api/wrap)"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [app  (api/make \"UIApplication\" \"sharedApplication\")
             win  (api/wrap \"UIWindow\" (api/call app \"keyWindow\"))]
         (some? (api/call win \"rootViewController\")))))"
  #(= "true" (:value %))
  :timeout 10000)

(run-suite "grease.ios.engine UIKit (Phase 4.2)")
