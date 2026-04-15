;; tests/test_engine.clj — Integration test for the data-driven engine (Phase 1.2).
;;
;; Verifies that grease.ios.api and its dependencies are correctly loaded
;; into the SCI context on device and can dispatch real ObjC messages.
;;
;; Tests:
;;   1. grease.ios.api namespace is accessible (no "could not find namespace")
;;   2. Type table is initialised — NSString encoding is "@"
;;   3. Registry is populated — NSString class-spec is non-nil
;;   4. NSString round-trip via engine (make + call UTF8String)
;;   5. NSMutableArray factory + count via engine
;;   6. CLLocationManager instantiation via engine
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_engine.clj

(load-file "tests/test_runner.clj")
(connect!)

;; ─── 1. Namespace accessibility ─────────────────────────────────────────────

(check "1. grease.ios.api namespace is accessible"
  "(do (require '[grease.ios.api :as api]) :ok)"
  #(= ":ok" (:value %)))

;; ─── 2. Type table initialised ──────────────────────────────────────────────

(check "2. type table has NSString with encoding \"@\""
  "(do
     (require '[grease.ios.types :as types])
     (= \"@\" (types/encoding-for \"NSString\")))"
  #(= "true" (:value %)))

;; ─── 3. Registry populated ──────────────────────────────────────────────────

(check "3. registry has NSString class-spec"
  "(do
     (require '[grease.ios.registry :as registry])
     (some? (registry/class-spec \"NSString\")))"
  #(= "true" (:value %)))

;; ─── 4. NSString round-trip ─────────────────────────────────────────────────

(check "4. NSString round-trip — make + UTF8String"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [obj (api/make \"NSString\" \"stringWithUTF8String:\" \"hello engine\")]
         (api/call obj \"UTF8String\"))))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "hello engine")))
  :timeout 10000)

;; ─── 5. NSMutableArray factory + count ──────────────────────────────────────

(check "5. NSMutableArray array + count returns 0"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (let [arr (api/make \"NSMutableArray\" \"array\")]
         (api/call arr \"count\"))))"
  #(and (:ok? %) (= "0" (:value %)))
  :timeout 10000)

;; ─── 6. CLLocationManager instantiation ─────────────────────────────────────

(check "6. CLLocationManager new via engine"
  "(do
     (require '[grease.ios.api :as api])
     (require '[grease.ios.repl :refer [on-main]])
     (on-main
       (some? (api/make \"CLLocationManager\" \"new\"))))"
  #(= "true" (:value %))
  :timeout 10000)

(run-suite "grease.ios.engine (Phase 1.2)")
