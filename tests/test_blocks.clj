;; tests/test_blocks.clj — Integration test for grease.ios.blocks.
;;
;; Tests:
;;   1. make-void-block + call-void-block! — Clojure fn fires when block invoked
;;   2. make-data-block — returns a non-nil pointer
;;   3. make-bool-error-block — returns a non-nil pointer
;;   4. URLSession data task via make-data-block — real HTTP GET, callback fires
;;
;; Usage:
;;   clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
;;       -M tests/test_blocks.clj

(load-file "tests/test_runner.clj")
(connect!)

(check "1. void block round-trip (make-void-block / call-void-block!)"
  "(do
     (require '[grease.ios.blocks :as blk])
     (def block-fired (atom false))
     (def held-blk (atom nil))
     (let [b (blk/make-void-block (fn [] (reset! block-fired true)))]
       (reset! held-blk b)
       (blk/call-void-block! b))
     @block-fired)"
  #(= "true" (:value %)))

(check "2. make-data-block returns non-nil pointer"
  "(do
     (require '[grease.ios.blocks :as blk])
     (def held-data-blk (atom nil))
     (let [b (blk/make-data-block (fn [data resp err] nil))]
       (reset! held-data-blk b)
       (some? b)))"
  #(= "true" (:value %)))

(check "3. make-bool-error-block returns non-nil pointer"
  "(do
     (require '[grease.ios.blocks :as blk])
     (def held-bool-blk (atom nil))
     (let [b (blk/make-bool-error-block (fn [ok err] nil))]
       (reset! held-bool-blk b)
       (some? b)))"
  #(= "true" (:value %)))

;; Step 4a: start the HTTP task (returns immediately)
(eval! "(do
  (require '[grease.ios.blocks :as blk])
  (require '[grease.ios.foundation :as f])
  (require '[grease.ios.objc :as objc-rt])
  (def http-result (atom nil))
  (def held-http-blk (atom nil))
  (let [g       com.phronemophobic.grease/get-objc-class
        session (objc-rt/msg-send :pointer (g \"NSURLSession\") \"sharedSession\")
        url     (objc-rt/msg-send :pointer (g \"NSURL\")
                  \"URLWithString:\" :pointer (f/->nsstring \"https://httpbin.org/get\"))
        req     (objc-rt/msg-send :pointer (g \"NSURLRequest\")
                  \"requestWithURL:\" :pointer url)
        handler (blk/make-data-block
                  (fn [data resp _err]
                    (reset! http-result
                            {:status (when resp (objc-rt/msg-send :int64 resp \"statusCode\"))
                             :bytes  (when data  (objc-rt/msg-send :int64 data  \"length\"))})))
        task    (objc-rt/msg-send :pointer session
                  \"dataTaskWithRequest:completionHandler:\"
                  :pointer req :pointer handler)]
    (reset! held-http-blk handler)
    (objc-rt/msg-send :void task \"resume\")
    :task-started))"
  {:timeout 5000})

(println "\n>>> Waiting 12s for HTTP response…\n")
(Thread/sleep 12000)

(check "4. URLSession GET callback fires (http-result non-nil)"
  "@http-result"
  #(and (:ok? %) (some-> (:value %) (not= "nil")))
  :timeout 5000)

(run-suite "grease.ios.blocks")
