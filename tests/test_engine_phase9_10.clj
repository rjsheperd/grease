;; tests/test_engine_phase9_10.clj — on-device tests for Phases 9.1, 9.2, 10.1
;;
;; Tests grease.ios.blocks/make-typed-block, the :block-args completion-handler
;; pattern, and grease.ios.structs on real device.
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

(check "10. structs/unpack CGPoint round-trips {:x 3.14 :y 2.71}"
  "(do
     (require '[grease.ios.structs :as s])
     (s/init!)
     (let [buf (s/pack \"CGPoint\" {:x 3.14 :y 2.71})
           out (s/unpack \"CGPoint\" buf)]
       [(< (Math/abs (- 3.14 (double (:x out)))) 1e-9)
        (< (Math/abs (- 2.71 (double (:y out)))) 1e-9)]))"
  #(= "[true true]" (:value %))
  :timeout 5000)

(run-suite "Phases 9.1 / 10.1 / 10.2.1 on-device")
