(ns grease.ios.completion-test
  (:require [clojure.test :refer [deftest is testing]]
            [grease.ios.blocks]
            [grease.ios.completion :as comp]
            [grease.ios.retain :as retain]))

;; ── with-completion (pure, using mock block factories) ───────────────────────

(defn- make-sync-block
  "Returns [block-ptr promise] where block-ptr is actually the fn itself
  (so tests can call it directly without the ObjC runtime)."
  [block-type]
  ;; Intercept the block factories to return the raw fn as the 'block pointer'
  (let [result (atom nil)]
    (with-redefs [grease.ios.blocks/make-void-block
                  (fn [f] (reset! result f) :void-block)
                  grease.ios.blocks/make-data-block
                  (fn [f] (reset! result f) :data-block)
                  grease.ios.blocks/make-bool-error-block
                  (fn [f] (reset! result f) :bool-error-block)
                  retain/retain! (fn [k _ _] k)
                  retain/release! (fn [_])]
      (let [[_blk p] (comp/with-completion block-type)]
        [@result p]))))

(deftest ^:parallel void-completion-test
  (testing ":void block delivers nil to promise"
    (let [[invoke-fn p] (make-sync-block :void)]
      (invoke-fn)
      (is (nil? @p)))))

(deftest ^:parallel data-completion-test
  (testing ":data block delivers {:data :response :error} map"
    (let [[invoke-fn p] (make-sync-block :data)]
      (invoke-fn :d :r :e)
      (is (= {:data :d :response :r :error :e} @p)))))

(deftest ^:parallel bool-error-true-test
  (testing ":bool-error block delivers {:ok? true} when ok=1"
    (let [[invoke-fn p] (make-sync-block :bool-error)]
      (invoke-fn (byte 1) nil)
      (is (true? (:ok? @p))))))

(deftest ^:parallel bool-error-false-test
  (testing ":bool-error block delivers {:ok? false} when ok=0"
    (let [[invoke-fn p] (make-sync-block :bool-error)]
      (invoke-fn (byte 0) :err)
      (is (false? (:ok? @p)))
      (is (= :err (:error @p))))))

(deftest ^:parallel unknown-block-type-throws-test
  (testing "unknown block-type throws ex-info"
    (with-redefs [retain/retain! (fn [k _ _] k)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown completion block type"
                            (comp/with-completion :unknown))))))

;; ── let-completion macro (pure, using pre-resolved promises) ─────────────────

(deftest ^:parallel let-completion-binds-test
  (testing "let-completion deref's promises and binds results"
    (let [p1 (doto (promise) (deliver 42))
          p2 (doto (promise) (deliver :hello))]
      (is (= [42 :hello]
             (comp/let-completion [a p1 b p2]
                                  [a b]))))))

(deftest ^:parallel let-completion-expansion-timeout-test
  (testing "let-completion expansion includes timeout sentinel and throw"
    (let [expanded (macroexpand-1 '(grease.ios.completion/let-completion [x p] x))
          forms    (tree-seq seq? seq expanded)]
      (is (some (fn [form]
                  (and (seq? form)
                       (= 'clojure.core/when (first form))))
                forms)
          "expansion includes a `when` timeout guard"))))

(deftest ^:parallel let-completion-body-result-test
  (testing "let-completion returns body value"
    (let [p (doto (promise) (deliver 7))]
      (is (= 49
             (comp/let-completion [n p]
                                  (* n n)))))))
