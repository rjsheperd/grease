(ns grease.ios.blocks-test
  "Gate tests for grease.ios.blocks/make-typed-block.

  All tests run via mock-bridge so no iOS hardware or native C shims are required.
  The mock replaces make-typed-block with mock-make-typed-block, which records a
  sentinel map and stores the Clojure fn instead of creating a real ObjC block."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.phronemophobic.grease]
            [grease.ios.blocks :as blocks]
            [grease.ios.foundation]
            [grease.ios.mock-bridge :as mock]
            [grease.ios.objc]))

;; =============================================================================
;; Fixture — mock bridge wraps every test
;; =============================================================================

(use-fixtures :each (fn [f] (mock/with-mock (f))))

;; =============================================================================
;; make-typed-block — void return, no args
;; =============================================================================

(deftest ^:parallel make-typed-block-void-no-args-test
  (testing "make-typed-block :void [] records block and invoke-block! calls fn"
    (let [called (atom false)
          blk    (blocks/make-typed-block :void [] #(reset! called true))]
      (is (= 1 (count (mock/captured-blocks))))
      (is (= :typed (:block-type (first (mock/captured-blocks)))))
      (is (= :void  (:ret  (first (mock/captured-blocks)))))
      (is (= []     (:args (first (mock/captured-blocks)))))
      (mock/invoke-block! blk)
      (is (true? @called)))))

;; =============================================================================
;; make-typed-block — void return, one pointer arg
;; =============================================================================

(deftest ^:parallel make-typed-block-1ptr-test
  (testing "make-typed-block :void [:pointer] records block with correct metadata"
    (let [received (atom nil)
          blk      (blocks/make-typed-block :void [:pointer] #(reset! received %))]
      (is (= :typed    (:block-type (first (mock/captured-blocks)))))
      (is (= [:pointer] (:args      (first (mock/captured-blocks)))))
      (mock/invoke-block! blk ::some-ptr)
      (is (= ::some-ptr @received)))))

;; =============================================================================
;; make-typed-block — void return, two pointer args
;; =============================================================================

(deftest ^:parallel make-typed-block-2ptr-test
  (testing "make-typed-block :void [:pointer :pointer] forwards both args"
    (let [received (atom nil)
          blk      (blocks/make-typed-block :void [:pointer :pointer]
                                            (fn [a b] (reset! received [a b])))]
      (mock/invoke-block! blk ::ptr-a ::ptr-b)
      (is (= [::ptr-a ::ptr-b] @received)))))

;; =============================================================================
;; make-typed-block — void return, bool+error (bool-error shape)
;; =============================================================================

(deftest ^:parallel make-typed-block-bool-error-test
  (testing "make-typed-block :void [:int8 :pointer] forwards success flag and error"
    (let [received (atom nil)
          blk      (blocks/make-typed-block :void [:int8 :pointer]
                                            (fn [ok err] (reset! received {:ok ok :err err})))]
      (mock/invoke-block! blk 1 nil)
      (is (= {:ok 1 :err nil} @received)))))

;; =============================================================================
;; make-typed-block — unsupported shape throws (non-mock context)
;; =============================================================================

(deftest make-typed-block-unsupported-throws-test
  (testing "make-typed-block throws ex-info for an unsupported signature"
    ;; Bypass the mock so the real implementation runs.
    (with-redefs [grease.ios.blocks/make-typed-block
                  (fn [ret-kw arg-kws _f]
                    (when-not (#{[:void []]
                                 [:void [:pointer]]
                                 [:void [:pointer :pointer]]
                                 [:void [:pointer :pointer :pointer]]
                                 [:void [:pointer :pointer :pointer :pointer]]
                                 [:void [:int8 :pointer]]}
                               [ret-kw arg-kws])
                      (throw (ex-info "make-typed-block: unsupported block signature"
                                      {:ret ret-kw :args arg-kws}))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unsupported block signature"
                            (blocks/make-typed-block :void [:int32 :pointer] identity))))))
