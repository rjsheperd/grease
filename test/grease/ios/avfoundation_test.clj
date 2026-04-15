(ns grease.ios.avfoundation-test
  "Tests for AVFoundation.edn spec — Phase 3.1 gate.

  Verifies that the spec loads correctly into the registry and that
  all key classes, methods, protocols, and enums are accessible.
  All tests run on plain JVM (no iOS hardware required).

  The completion-handler pattern on requestAccessForMediaType:completionHandler:
  and the delegate pattern on setSampleBufferDelegate:queue: are validated
  via the patterns/wrap-arg pipeline with a mock bridge."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.api :as api]
            [grease.ios.foundation]
            [grease.ios.mock-bridge :as mock]
            [grease.ios.patterns :as patterns]
            [grease.ios.registry :as registry]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (api/load!)
    (f)))

;; =============================================================================
;; Spec loading — classes
;; =============================================================================

(deftest ^:parallel avcapturedevice-spec-test
  (testing "AVCaptureDevice class spec loads with all init methods"
    (let [cs (registry/class-spec "AVCaptureDevice")]
      (is (some? cs))
      (is (= "AVCaptureDevice" (:name cs)))
      (let [init-sels (set (map :selector (:init cs)))]
        (is (contains? init-sels "authorizationStatusForMediaType:"))
        (is (contains? init-sels "defaultDeviceWithMediaType:"))
        (is (contains? init-sels "requestAccessForMediaType:completionHandler:"))))))

(deftest ^:parallel avcapturesession-spec-test
  (testing "AVCaptureSession class spec loads with all instance methods"
    (let [cs (registry/class-spec "AVCaptureSession")]
      (is (some? cs))
      (let [sels (set (map :selector (:methods cs)))]
        (is (contains? sels "beginConfiguration"))
        (is (contains? sels "commitConfiguration"))
        (is (contains? sels "addInput:"))
        (is (contains? sels "addOutput:"))
        (is (contains? sels "canAddInput:"))
        (is (contains? sels "canAddOutput:"))
        (is (contains? sels "startRunning"))
        (is (contains? sels "stopRunning"))
        (is (contains? sels "isRunning"))))))

(deftest ^:parallel avcapturedeviceinput-spec-test
  (testing "AVCaptureDeviceInput class spec has deviceInputWithDevice:error:"
    (let [m (registry/method-spec "AVCaptureDeviceInput" "deviceInputWithDevice:error:")]
      (is (some? m))
      (is (= "@@:@@" (:encoding m)))
      (is (= "id" (:return m))))))

(deftest ^:parallel avcapturevideodataoutput-spec-test
  (testing "AVCaptureVideoDataOutput has setSampleBufferDelegate:queue: with delegate pattern"
    (let [m (registry/method-spec "AVCaptureVideoDataOutput" "setSampleBufferDelegate:queue:")]
      (is (some? m))
      (is (= :delegate (:pattern (first (:args m)))))
      (is (= "AVCaptureVideoDataOutputSampleBufferDelegate"
             (:protocol (first (:args m))))))))

;; =============================================================================
;; Spec loading — protocol and enums
;; =============================================================================

(deftest ^:parallel avcapture-delegate-protocol-test
  (testing "AVCaptureVideoDataOutputSampleBufferDelegate protocol is accessible"
    (let [proto (registry/protocol-spec "AVCaptureVideoDataOutputSampleBufferDelegate")]
      (is (some? proto))
      (is (= "AVCaptureVideoDataOutputSampleBufferDelegate" (:name proto)))
      (let [sels (set (map :selector (:methods proto)))]
        (is (contains? sels "captureOutput:didOutputSampleBuffer:fromConnection:"))
        (is (contains? sels "captureOutput:didDropSampleBuffer:fromConnection:"))))))

(deftest ^:parallel avauthorizationstatus-enum-test
  (testing "AVAuthorizationStatus enum values are accessible"
    (is (= 0 (registry/enum-raw-for "AVAuthorizationStatus" :av-authorization-status-not-determined)))
    (is (= 1 (registry/enum-raw-for "AVAuthorizationStatus" :av-authorization-status-restricted)))
    (is (= 2 (registry/enum-raw-for "AVAuthorizationStatus" :av-authorization-status-denied)))
    (is (= 3 (registry/enum-raw-for "AVAuthorizationStatus" :av-authorization-status-authorized)))))

(deftest ^:parallel avauthorizationstatus-via-api-enum-test
  (testing "api/enum returns raw int for AVAuthorizationStatus"
    (is (= 3 (api/enum "AVAuthorizationStatus" :av-authorization-status-authorized)))))

;; =============================================================================
;; Method specs — encoding and return types
;; =============================================================================

(deftest ^:parallel isrunning-method-spec-test
  (testing "isRunning method spec has BOOL return and B@: encoding"
    (let [m (registry/method-spec "AVCaptureSession" "isRunning")]
      (is (some? m))
      (is (= "B@:" (:encoding m)))
      (is (= "BOOL" (:return m))))))

(deftest ^:parallel authorizationstatus-method-spec-test
  (testing "authorizationStatusForMediaType: returns NSInteger with q@:@ encoding"
    (let [m (registry/method-spec "AVCaptureDevice" "authorizationStatusForMediaType:")]
      (is (some? m))
      (is (= "q@:@" (:encoding m)))
      (is (= "NSInteger" (:return m))))))

;; =============================================================================
;; Completion handler arg spec
;; =============================================================================

(deftest ^:parallel completion-handler-arg-spec-test
  (testing "requestAccessForMediaType:completionHandler: has :completion-handler arg"
    (let [m    (registry/method-spec "AVCaptureDevice"
                                     "requestAccessForMediaType:completionHandler:")
          args (:args m)]
      (is (= 2 (count args)))
      (is (= :completion-handler (:pattern (second args))))
      (is (= :bool-error (:block-type (second args)))))))

;; =============================================================================
;; Completion handler via patterns/wrap-arg
;; =============================================================================

(deftest completion-handler-block-created-test
  (testing "wrap-arg creates bool-error block for requestAccessForMediaType:completionHandler:"
    (mock/with-mock
      (let [arg-spec {:name "handler" :type "id"
                      :pattern :completion-handler :block-type :bool-error}
            blk      (patterns/wrap-arg arg-spec (fn [_ _] nil))]
        (is (= :bool-error (:block-type blk)))
        (is (= 1 (count (mock/captured-blocks))))))))

;; =============================================================================
;; Delegate wiring via patterns/wrap-delegate
;; =============================================================================

(deftest avcapture-delegate-selector-resolution-test
  (testing "wrap-delegate resolves both AVCaptureVideoDataOutputSampleBufferDelegate selectors"
    (let [captured (atom nil)]
      (with-redefs [patterns/create-delegate-class!
                    (fn [_ _ sel-enc-fns]
                      (reset! captured (mapv first sel-enc-fns))
                      {:mocked true})]
        (mock/with-mock
          (patterns/wrap-delegate
           {:capture-output-did-output-sample-buffer-from-connection (fn [& _] nil)
            :capture-output-did-drop-sample-buffer-from-connection   (fn [& _] nil)}
           "AVCaptureVideoDataOutputSampleBufferDelegate")
          (let [sels (set @captured)]
            (is (contains? sels "captureOutput:didOutputSampleBuffer:fromConnection:"))
            (is (contains? sels "captureOutput:didDropSampleBuffer:fromConnection:"))))))))

;; =============================================================================
;; api/call pipeline with BOOL return (JVM-safe — BOOL coercer is pure Clojure)
;; =============================================================================

(deftest isrunning-dispatch-bool-test
  (testing "api/call isRunning returns BOOL coerced from 0 (mocked)"
    (mock/with-responses {"isRunning" 0}
      (let [session (api/wrap "AVCaptureSession" {:address 0xCAFE})]
        (is (= false (api/call session "isRunning")))
        (is (= 1 (count (mock/calls))))
        (is (= "isRunning" (:sel (first (mock/calls)))))))))

(deftest canaddinput-dispatch-bool-test
  (testing "api/call canAddInput: returns BOOL coerced from 1 (mocked)"
    (mock/with-responses {"canAddInput:" 1}
      (let [session (api/wrap "AVCaptureSession" {:address 0xCAFE})
            input   {:address 0xB00F}]
        (is (= true (api/call session "canAddInput:" input)))))))
