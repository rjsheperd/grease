;; tests/test_engine_phase9_10.clj — on-device tests for Phases 9.1, 10.1, 10.2.1, 10.2.4
;;
;; Tests grease.ios.blocks/make-typed-block, the :block-args completion-handler
;; pattern, grease.ios.structs, and struct-return dispatch via ffi/call-ptr.
;;
;; Prerequisites: App running, nREPL on port 23456.
;; Usage: clj -M tests/test_engine_phase9_10.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── Phase 9.1: make-typed-block ────────────────────────────────────────────

(check "1. grease.ios.blocks namespace loads"
  "(do (require '[grease.ios.blocks :as blk]) true)"
  #(= "true" (:value %)))

(check "2. make-typed-block :void [] exists and is a fn"
  "(do
     (require '[grease.ios.blocks :as blk])
     (fn? blk/make-typed-block))"
  #(= "true" (:value %))
  :timeout 5000)

(check "3. make-typed-block :void [] creates a block (non-nil)"
  "(do
     (require '[grease.ios.blocks :as blk])
     (some? (blk/make-typed-block :void [] (fn [] nil))))"
  #(= "true" (:value %))
  :timeout 5000)

(check "4. make-typed-block :void [:pointer] creates a block (non-nil)"
  "(do
     (require '[grease.ios.blocks :as blk])
     (some? (blk/make-typed-block :void [:pointer] (fn [_] nil))))"
  #(= "true" (:value %))
  :timeout 5000)

(check "5. make-typed-block :void [:int8 :pointer] creates a bool-error block"
  "(do
     (require '[grease.ios.blocks :as blk])
     (some? (blk/make-typed-block :void [:int8 :pointer] (fn [_ _] nil))))"
  #(= "true" (:value %))
  :timeout 5000)

;; ─── Phase 10.1: structs.edn ────────────────────────────────────────────────

(check "6. grease.ios.structs namespace loads and init! works"
  "(do (require '[grease.ios.structs :as s]) (s/init!) true)"
  #(= "true" (:value %))
  :timeout 5000)

(check "7. struct-for CGRect returns spec with :size 32"
  "(do
     (require '[grease.ios.structs :as s])
     (s/init!)
     (:size (s/struct-for \"CGRect\")))"
  #(= "32" (:value %))
  :timeout 5000)

(check "8. known-struct? returns true for CGRect, false for NSString"
  "(do
     (require '[grease.ios.structs :as s])
     (s/init!)
     [(s/known-struct? \"CGRect\") (s/known-struct? \"NSString\")])"
  #(= "[true false]" (:value %))
  :timeout 5000)

;; ─── Phase 10.2.1: structs pack/unpack ──────────────────────────────────────

(check "9. structs/pack CGPoint returns ByteBuffer of capacity 16"
  "(do
     (require '[grease.ios.structs :as s])
     (s/init!)
     (let [buf (s/pack \"CGPoint\" {:x 1.0 :y 2.0})]
       (.capacity buf)))"
  #(= "16" (:value %))
  :timeout 5000)

;; NOTE: Using chained comparison instead of Math/abs — Math is not available
;; in the frozen SCI native image (not in reflectionconfig-arm64-ios.json).
(check "10. structs/unpack CGPoint round-trips {:x 3.14 :y 2.71}"
  "(do
     (require '[grease.ios.structs :as s])
     (s/init!)
     (let [buf (s/pack \"CGPoint\" {:x 3.14 :y 2.71})
           out (s/unpack \"CGPoint\" buf)]
       [(< -1e-9 (- (double (:x out)) 3.14) 1e-9)
        (< -1e-9 (- (double (:y out)) 2.71) 1e-9)]))"
  #(= "[true true]" (:value %))
  :timeout 5000)

;; ─── Phase 10.2.4: struct-return dispatch via ffi/call-ptr ──────────────────
;;
;; Tests invoke/dispatch! struct-return path using grease.ios.api.
;; Creates a real NSMutableArray (a CGRect-returning method is not easy to
;; test without a UIView), so we verify the API and struct registry are wired
;; correctly and that struct-names returns a non-empty set.

(check "11. api/load! registers CLLocationCoordinate2D in structs/struct-names"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.structs :as s])
     (api/load!)
     (contains? (s/struct-names) \"CLLocationCoordinate2D\"))"
  #(= "true" (:value %))
  :timeout 5000)

(check "12. CLLocation coordinate method-spec has CLLocationCoordinate2D return"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.registry :as registry])
     (api/load!)
     (= \"CLLocationCoordinate2D\"
        (:return (registry/method-spec \"CLLocation\" \"coordinate\"))))"
  #(= "true" (:value %))
  :timeout 5000)

(check "13. structs/known-struct? true for CLLocationCoordinate2D after api/load!"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.structs :as s])
     (api/load!)
     (s/known-struct? \"CLLocationCoordinate2D\"))"
  #(= "true" (:value %))
  :timeout 5000)

(check "14. invoke/dispatch! dispatches addObject: on real NSMutableArray (auto-coerce)"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.foundation :as f])
     (require '[grease.ios.invoke :as invoke])
     (require '[grease.ios.registry :as registry])
     (api/load!)
     (let [arr     (f/->nsarray [])
           add-m   (registry/method-spec \"NSMutableArray\" \"addObject:\")
           count-m (registry/method-spec \"NSArray\" \"count\")]
       (invoke/dispatch! arr \"addObject:\" add-m [\"hello\"])
       (= 1 (invoke/dispatch! arr \"count\" count-m []))))"
  #(= "true" (:value %))
  :timeout 5000)

(run-suite "Phases 9.1 / 10.1 / 10.2.1 / 10.2.4 on-device")
