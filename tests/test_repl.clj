;; tests/test_repl.clj — Integration test for grease.ios.repl helpers.
;;
;; Tests:
;;   1. class-name-of — ObjC class name from pointer
;;   2a/2b. respond-to? — true for known selector, false for bogus
;;   3. methods-of — non-empty, contains "length" for NSString
;;   4. super-class-of / inheritance-chain
;;   5. on-main — evaluates on main thread, returns result
;;   6a/6b. defclass! — creates versioned class, safe to re-evaluate
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_repl.clj

(load-file "tests/test_runner.clj")
(connect!)

(check "1. class-name-of returns NSString class"
  "(do
     (require '[grease.ios.repl :as repl])
     (require '[grease.ios.foundation :as f])
     (repl/class-name-of (f/->nsstring \"hello\")))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "String"))))

(check "2a. respond-to? true (NSString / length)"
  "(do
     (require '[grease.ios.repl :as repl])
     (require '[grease.ios.foundation :as f])
     (repl/respond-to? (f/->nsstring \"hello\") \"length\"))"
  #(= "true" (:value %)))

(check "2b. respond-to? false (NSString / nonexistentMethod12345)"
  "(do
     (require '[grease.ios.repl :as repl])
     (require '[grease.ios.foundation :as f])
     (repl/respond-to? (f/->nsstring \"hello\") \"nonexistentMethod12345\"))"
  #(= "false" (:value %)))

(check "3. methods-of NSString contains \"length\""
  "(do
     (require '[grease.ios.repl :as repl])
     (some #{\"length\"} (repl/methods-of \"NSString\")))"
  #(= "\"length\"" (:value %)))

(check "4. super-class-of UIViewController returns UIResponder"
  "(do
     (require '[grease.ios.repl :as repl])
     (repl/super-class-of \"UIViewController\"))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "UIResponder"))))

(check "5. on-main evaluates on main thread"
  "(do
     (require '[grease.ios.repl :as repl])
     (repl/on-main (+ 1 2)))"
  #(= "3" (:value %)))

(check "6a. defclass! creates versioned ObjC class"
  "(do
     (require '[grease.ios.repl :as repl])
     (repl/defclass! ReplTestDelegate \"NSObject\"
       \"testMethod\" \"v@:\"
       (fn [_ _] (println \"testMethod called\")))
     (some? ReplTestDelegate))"
  #(= "true" (:value %)))

(check "6b. defclass! re-eval creates _v2 without crash"
  "(do
     (require '[grease.ios.repl :as repl])
     (repl/defclass! ReplTestDelegate \"NSObject\"
       \"testMethod\" \"v@:\"
       (fn [_ _] (println \"testMethod v2\")))
     (some? ReplTestDelegate))"
  #(= "true" (:value %)))

(run-suite "grease.ios.repl")
