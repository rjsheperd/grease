;; tests/test_foundation.clj — Integration test for grease.ios.foundation.
;;
;; Tests:
;;   1. NSString round-trip
;;   2. NSNumber long round-trip
;;   3. NSNumber double round-trip
;;   4. NSArray round-trip
;;   5. NSDictionary string round-trip
;;   6. NSError -> map
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_foundation.clj
;;   NREPL_HOST=10.0.0.1 scripts/repl-eval < tests/test_foundation.clj  # not recommended

(load-file "tests/test_runner.clj")
(connect!)

(check "1. NSString round-trip"
  "(do
     (require '[grease.ios.foundation :as f])
     (f/nsstring->str (f/->nsstring \"hello from Clojure\")))"
  #(= "\"hello from Clojure\"" (:value %)))

(check "2. NSNumber long round-trip"
  "(do
     (require '[grease.ios.foundation :as f])
     (f/nsnumber->long (f/->nsnumber-long 42)))"
  #(= "42" (:value %)))

(check "3. NSNumber double round-trip"
  "(do
     (require '[grease.ios.foundation :as f])
     (str (f/nsnumber->double (f/->nsnumber-double 3.14))))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "3.14"))))

(check "4. NSArray round-trip"
  "(do
     (require '[grease.ios.foundation :as f])
     (mapv f/nsstring->str
           (f/nsarray->vec
             (f/->nsarray (mapv f/->nsstring [\"alpha\" \"beta\" \"gamma\"])))))"
  #(= "[\"alpha\" \"beta\" \"gamma\"]" (:value %)))

(check "5. NSDictionary string round-trip"
  "(do
     (require '[grease.ios.foundation :as f])
     (into (sorted-map)
           (f/nsdict->map-str (f/->nsdict-str {\"key1\" \"val1\" \"key2\" \"val2\"}))))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "key1"))))

(check "6. NSError -> map"
  "(do
     (require '[grease.ios.foundation :as f])
     (require '[grease.ios.objc :as objc-rt])
     (select-keys
       (f/nserror->map
         (objc-rt/msg-send :pointer
           (com.phronemophobic.grease/get-objc-class \"NSError\")
           \"errorWithDomain:code:userInfo:\"
           :pointer (f/->nsstring \"com.grease.test\")
           :int64 42
           :pointer (f/->nsdict {})))
       [:domain :code]))"
  #(and (:ok? %) (some-> (:value %) (clojure.string/includes? "42"))))

(run-suite "grease.ios.foundation")
