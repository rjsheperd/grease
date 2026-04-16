;; tests/test_engine_coerce.clj — Phase 8.1 on-device coerce validation.
;;
;; Validates grease.ios.coerce on real hardware (requires Phase 8.2 to wire
;; coerce into invoke/dispatch! so that ->ns-object is called automatically).
;;
;; Until 8.2 is done, these tests exercise coerce directly via REPL eval.
;;
;; Prerequisites:
;;   - App running on device (debug-demo-app --noinstall)
;;
;; Usage:
;;   clj -M tests/test_engine_coerce.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. Module loads ─────────────────────────────────────────────────────────

(check "1. grease.ios.coerce namespace loads"
  "(do (require '[grease.ios.coerce :as coerce]) true)"
  #(= "true" (:value %)))

;; ─── 2. String → NSString round-trip ─────────────────────────────────────────

(check "2. ->ns-object String round-trips via from-ns-object"
  "(do
     (require '[grease.ios.coerce :as coerce])
     (require '[grease.ios.foundation :as f])
     (let [ptr (coerce/->ns-object \"hello coerce\")]
       (coerce/from-ns-object \"NSString\" ptr)))"
  #(= "\"hello coerce\"" (:value %))
  :timeout 10000)

;; ─── 3. Long → NSNumber round-trip ───────────────────────────────────────────

(check "3. ->ns-object Long round-trips via from-ns-object"
  "(do
     (require '[grease.ios.coerce :as coerce])
     (let [ptr (coerce/->ns-object 42)]
       (coerce/from-ns-object \"NSNumber\" ptr)))"
  #(= "42" (:value %))
  :timeout 10000)

;; ─── 4. nil → null-ptr (non-throwing) ────────────────────────────────────────

(check "4. ->ns-object nil returns non-nil null-ptr"
  "(do
     (require '[grease.ios.coerce :as coerce])
     (some? (coerce/->ns-object nil)))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 5. Vector → NSArray ─────────────────────────────────────────────────────

(check "5. ->ns-object vector returns non-nil NSArray pointer"
  "(do
     (require '[grease.ios.coerce :as coerce])
     (some? (coerce/->ns-object [\"a\" \"b\" \"c\"])))"
  #(= "true" (:value %))
  :timeout 10000)

;; ─── 6. Passthrough for unknown type ─────────────────────────────────────────

(check "6. ->ns-object passthrough for non-Clojure-value"
  "(do
     (require '[grease.ios.coerce :as coerce])
     (= ::sentinel (coerce/->ns-object ::sentinel)))"
  #(= "true" (:value %))
  :timeout 10000)

(run-suite "grease.ios.coerce (Phase 8.1)")
