(ns grease.ios.mock-bridge
  "Recording mock for ObjC bridge functions used by the engine.

  Replaces the real FFI-backed functions with pure Clojure stubs that record
  every call and return canned responses.  Use in JVM tests without iOS hardware.

  Usage:
    (require '[grease.ios.mock-bridge :as mock])

    ;; basic gate test
    (mock/with-mock
      (grease.ios.invoke/call {:class-name \"AVPlayer\"} :play)
      (mock/calls))
    ;; => [{:ret-type :void :obj {:class-name \"AVPlayer\"} :sel \"play\" :args []}]

    ;; with canned response
    (mock/with-responses {\"status\" 1}
      (grease.ios.invoke/get-prop {:class-name \"AVPlayer\"} :status))
    ;; => 1  (raw int, before enum decode)"
  (:require [clojure.test :refer [is]]
            [grease.ios.blocks]))

;; =============================================================================
;; Call recorder
;; =============================================================================

(def ^:private call-log (atom []))
(def ^:private response-map (atom {}))
;; Captured block sentinels from mock-make-*-block.
(def ^:private block-log (atom []))

(defn calls
  "Returns the list of recorded calls since last clear! or with-mock."
  []
  @call-log)

(defn captured-blocks
  "Returns the list of block sentinels captured since last clear! or with-mock."
  []
  @block-log)

(defn clear!
  "Clears the call log, response map, and block log."
  []
  (reset! call-log [])
  (reset! response-map {})
  (reset! block-log []))

(defn set-response!
  "Registers a canned return value for a given ObjC selector string."
  [selector value]
  (swap! response-map assoc selector value))

;; =============================================================================
;; Mock implementations
;; =============================================================================

(defn mock-msg-send
  "Records call and returns canned response or nil."
  [ret-type obj sel & typed-args]
  (swap! call-log conj {:ret-type ret-type
                        :obj      obj
                        :sel      sel
                        :args     (vec typed-args)})
  (get @response-map sel nil))

(defn mock-get-class
  "Returns a synthetic class pointer map."
  [class-name]
  {:class-name class-name})

(defn mock-objc-new
  "Returns a synthetic instance pointer map."
  [cls]
  {:instance-of cls})

(defn mock-defclass!
  "Records a defclass! call. Returns a synthetic class pointer.
  Use this as a replacement for [[grease.ios.repl/defclass!]] in delegate tests."
  [class-name superclass & _method-specs]
  (swap! call-log conj {:op :defclass! :class-name class-name :superclass superclass})
  {:class-name class-name :mocked true})

(defn mock-null-ptr
  "Returns a sentinel value for null pointers."
  []
  ::null-ptr)

(defn mock-main-queue
  "Returns a sentinel for the main GCD queue."
  []
  ::main-queue)

;; =============================================================================
;; Block mock implementations
;; =============================================================================

(defn mock-make-void-block
  "Records block creation and returns a sentinel map containing the wrapped fn."
  [f]
  (let [sentinel {:block-type :void :fn f}]
    (swap! block-log conj sentinel)
    sentinel))

(defn mock-make-data-block
  "Records block creation and returns a sentinel map containing the wrapped fn."
  [f]
  (let [sentinel {:block-type :data :fn f}]
    (swap! block-log conj sentinel)
    sentinel))

(defn mock-make-bool-error-block
  "Records block creation and returns a sentinel map containing the wrapped fn."
  [f]
  (let [sentinel {:block-type :bool-error :fn f}]
    (swap! block-log conj sentinel)
    sentinel))

(defn mock-make-typed-block
  "Records a typed block creation and returns a sentinel map containing the wrapped fn."
  [ret-kw arg-kws f]
  (let [sentinel {:block-type :typed :ret ret-kw :args arg-kws :fn f}]
    (swap! block-log conj sentinel)
    sentinel))

(defn invoke-block!
  "Invokes a captured block sentinel's wrapped fn with the given args.
  Use in tests to simulate the iOS runtime invoking a completion handler."
  [block & args]
  (apply (:fn block) args))

;; =============================================================================
;; with-mock macro
;; =============================================================================

(defmacro with-mock
  "Runs body with all bridge functions replaced by recording mocks.
  Clears the call log and block log before executing body.
  Returns the value of body."
  [& body]
  `(with-redefs [grease.ios.objc/msg-send                  mock-msg-send
                 com.phronemophobic.grease/get-objc-class   mock-get-class
                 com.phronemophobic.grease/objc-new         mock-objc-new
                 grease.ios.foundation/null-ptr             mock-null-ptr
                 grease.ios.foundation/main-queue           mock-main-queue
                 grease.ios.blocks/make-void-block          mock-make-void-block
                 grease.ios.blocks/make-data-block          mock-make-data-block
                 grease.ios.blocks/make-bool-error-block    mock-make-bool-error-block
                 grease.ios.blocks/make-typed-block         mock-make-typed-block]
     (clear!)
     ~@body))

(defmacro with-responses
  "Like with-mock, but pre-loads a selector → value response map."
  [responses & body]
  `(with-mock
     (doseq [[sel# val#] ~responses]
       (set-response! sel# val#))
     ~@body))

;; =============================================================================
;; Assertion helpers
;; =============================================================================

(defn assert-called
  "Asserts that sel was called at least once in the call log.
  Returns the matching call records."
  [sel]
  (let [matches (filter #(= (:sel %) sel) @call-log)]
    (is (seq matches) (str "Expected selector \"" sel "\" to be called; log: " @call-log))
    matches))

(defn assert-not-called
  "Asserts that sel was never called."
  [sel]
  (let [matches (filter #(= (:sel %) sel) @call-log)]
    (is (empty? matches) (str "Expected selector \"" sel "\" NOT to be called; log: " @call-log))))

(defn assert-call-count
  "Asserts that sel was called exactly n times."
  [sel n]
  (let [matches (filter #(= (:sel %) sel) @call-log)]
    (is (= n (count matches))
        (str "Expected \"" sel "\" to be called " n " time(s), got " (count matches)))))
