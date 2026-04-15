(ns grease.ios.camera
  "AVFoundation camera capture helpers for the nREPL.

  Uses [[grease.ios.blocks/make-bool-error-block]] for permission callbacks
  and [[grease.ios.repl/defclass!]] for the AVCaptureVideoDataOutputSampleBufferDelegate.

  Quick reference:

    ;; Check authorization status
    (camera/authorization-status)  ;; => :not-determined :authorized :denied :restricted

    ;; Request permission (triggers system dialog on first call)
    (on-main (camera/request-authorization! (fn [granted?] (println \"granted:\" granted?))))

    ;; Start capturing video frames
    (on-main (camera/start-session!))
    @camera/last-frame      ;; => ObjC CMSampleBufferRef pointer (or nil)
    @camera/frame-count     ;; => number of frames received

    ;; Stop capturing
    (on-main (camera/stop-session!))"
  (:require [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.grease :as grease]
            [grease.ios.blocks :as blocks]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]
            [grease.ios.repl :refer [defclass!]]))

;; =============================================================================
;; State atoms
;; =============================================================================

(def ^{:doc "Most recent CMSampleBufferRef pointer, or nil."}
  last-frame (atom nil))

(def ^{:doc "Total number of sample buffer callbacks received."}
  frame-count (atom 0))

(def ^:private session-atom (atom nil))
(def ^:private output-atom (atom nil))
(def ^:private delegate-atom (atom nil))

;; =============================================================================
;; Authorization
;; =============================================================================

(def ^:private auth-status-keywords
  {0 :not-determined
   1 :restricted
   2 :denied
   3 :authorized})

(defn authorization-status
  "Returns the current AVAuthorizationStatus for video as a keyword."
  []
  (let [media-type (f/->nsstring "vide")   ; AVMediaTypeVideo string constant
        status (objc-rt/msg-send :int64
                                 (grease/get-objc-class "AVCaptureDevice")
                                 "authorizationStatusForMediaType:"
                                 :pointer media-type)]
    (get auth-status-keywords status :unknown)))

(defn request-authorization!
  "Requests camera access. callback is called with a boolean granted? arg.
  Must be called from a non-main thread (iOS requirement); the system dialog
  appears automatically."
  [callback]
  (let [media-type (f/->nsstring "vide")
        blk (blocks/make-bool-error-block
             (fn [granted _err]
               (callback (not= 0 granted))))]
    (objc-rt/msg-send :void
                      (grease/get-objc-class "AVCaptureDevice")
                      "requestAccessForMediaType:completionHandler:"
                      :pointer media-type
                      :pointer blk)))

;; =============================================================================
;; Sample buffer delegate
;; =============================================================================

(defclass! GrCameraDelegate "NSObject"

  "captureOutput:didOutputSampleBuffer:fromConnection:" "v@:@@@"
  (fn [_self _cmd _output sample-buf _conn]
    (reset! last-frame sample-buf)
    (swap! frame-count inc))

  "captureOutput:didDropSampleBuffer:fromConnection:" "v@:@@@"
  (fn [_self _cmd _output _buf _conn]
    ;; Dropped frames are normal when processing is slower than capture rate
    nil))

;; =============================================================================
;; Session management
;; =============================================================================

(defn- make-back-camera
  "Returns the default wide-angle back camera, or nil."
  []
  (objc-rt/msg-send :pointer
                    (grease/get-objc-class "AVCaptureDevice")
                    "defaultDeviceWithMediaType:"
                    :pointer (f/->nsstring "vide")))

(defn start-session!
  "Creates and starts an AVCaptureSession with the back camera at 30 fps.
  Frames arrive in [[last-frame]]; count in [[frame-count]].
  Must be called on the main thread."
  []
  (let [session (objc-rt/msg-send :pointer
                                  (grease/get-objc-class "AVCaptureSession")
                                  "new")
        camera  (make-back-camera)
        input   (objc-rt/msg-send :pointer
                                  (grease/get-objc-class "AVCaptureDeviceInput")
                                  "deviceInputWithDevice:error:"
                                  :pointer camera
                                  :pointer (f/null-ptr))
        output  (objc-rt/msg-send :pointer
                                  (grease/get-objc-class "AVCaptureVideoDataOutput")
                                  "new")
        d       (objc-rt/new-instance GrCameraDelegate)
        queue   (ffi/call "dispatch_queue_create" :pointer
                          :pointer (f/->nsstring "grease.camera.queue")
                          :pointer (f/null-ptr))]
    (reset! session-atom session)
    (reset! output-atom output)
    (reset! delegate-atom d)
    (reset! frame-count 0)
    (reset! last-frame nil)
    (objc-rt/msg-send :void session "beginConfiguration")
    (when (objc-rt/msg-send :int8 session "canAddInput:" :pointer input)
      (objc-rt/msg-send :void session "addInput:" :pointer input))
    (when (objc-rt/msg-send :int8 session "canAddOutput:" :pointer output)
      (objc-rt/msg-send :void session "addOutput:" :pointer output))
    (objc-rt/msg-send :void output
                      "setSampleBufferDelegate:queue:"
                      :pointer d
                      :pointer queue)
    (objc-rt/msg-send :void session "commitConfiguration")
    (objc-rt/msg-send :void session "startRunning")
    (println "[camera] session started")
    session))

(defn stop-session!
  "Stops the capture session. Noop if never started."
  []
  (when-let [s @session-atom]
    (objc-rt/msg-send :void s "stopRunning")
    (println "[camera] session stopped")))

(defn running?
  "Returns true if the session is currently running."
  []
  (when-let [s @session-atom]
    (not= 0 (objc-rt/msg-send :int8 s "isRunning"))))
