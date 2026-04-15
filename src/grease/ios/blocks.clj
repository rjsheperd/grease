(ns grease.ios.blocks
  "ObjC block factory for the Clojure nREPL.

  Wraps plain Clojure fns in heap-allocated ObjC blocks so they can be
  passed to iOS APIs that expect block-typed completion handlers
  (URLSession, UIView animations, HealthKit auth, etc.).

  Under the hood each block is a `grease_make_*_block` C shim that copies a
  `clj-libffi` ffi_closure pointer into a `Block_copy` heap block.  When iOS
  invokes the block, control flows through the ffi_closure trampoline and
  into the Clojure fn.

  Ownership: block pointers are +1 retained by the shim.  Store them in atoms
  to keep them alive past the current scope (same rule as ObjC delegates).

  Example — URLSession data task:

    (require '[grease.ios.blocks :as blk])
    (require '[grease.ios.foundation :as f])
    (require '[grease.ios.objc :as objc-rt])

    (def last-response (atom nil))
    (def held-block    (atom nil))

    (let [session (objc-rt/msg-send :pointer
                    (com.phronemophobic.grease/get-objc-class \"NSURLSession\")
                    \"sharedSession\")
          url     (objc-rt/msg-send :pointer
                    (com.phronemophobic.grease/get-objc-class \"NSURL\")
                    \"URLWithString:\" :pointer (f/->nsstring \"https://example.com\"))
          req     (objc-rt/msg-send :pointer
                    (com.phronemophobic.grease/get-objc-class \"NSURLRequest\")
                    \"requestWithURL:\" :pointer url)
          handler (blk/make-data-block
                   (fn [data resp _err]
                     (reset! last-response
                             {:status (objc-rt/msg-send :int64 resp \"statusCode\")
                              :bytes  (objc-rt/msg-send :int64 data \"length\")})))
          task    (objc-rt/msg-send :pointer session
                    \"dataTaskWithRequest:completionHandler:\"
                    :pointer req :pointer handler)]
      (reset! held-block handler)
      (objc-rt/msg-send :void task \"resume\"))

    ;; After a moment:
    @last-response  ;; => {:status 200, :bytes 1256}"
  (:require [com.phronemophobic.clj-libffi :as ffi]))

;; Prevent GC of live block objects and their underlying ffi_closures.
(def ^:private live-blocks (atom []))

(defn- make-callback*
  "Creates an ffi_closure trampoline with the given arg-types (no self/_cmd prefix).
  Retains the closure in live-blocks to prevent GC."
  [f arg-types]
  (let [make-callback (requiring-resolve 'com.phronemophobic.clj-libffi.callback/make-callback)
        cb (make-callback f :void arg-types)]
    (swap! live-blocks conj cb)
    cb))

(defn make-void-block
  "Returns an ObjC heap block `void (^)(void)` that calls Clojure fn f with no args.
  Hold the returned pointer in an atom to prevent early release."
  [f]
  (let [cb (make-callback* f [])]
    (ffi/call "grease_make_void_block" :pointer :pointer cb)))

(defn make-data-block
  "Returns an ObjC heap block `void (^)(NSData*, NSURLResponse*, NSError*)`.
  f receives three pointer args: (data response error).
  Hold the returned pointer in an atom to prevent early release."
  [f]
  (let [cb (make-callback* f [:pointer :pointer :pointer])]
    (ffi/call "grease_make_data_block" :pointer :pointer cb)))

(defn make-bool-error-block
  "Returns an ObjC heap block `void (^)(BOOL, NSError*)`.
  f receives two args: (success? error-ptr) where success? is 1 or 0.
  Used for HealthKit auth, UNUserNotificationCenter requests, etc.
  Hold the returned pointer in an atom to prevent early release."
  [f]
  (let [cb (make-callback* f [:int8 :pointer])]
    (ffi/call "grease_make_bool_error_block" :pointer :pointer cb)))

(defn call-void-block!
  "Invokes a void block by its opaque pointer. Useful for testing block creation."
  [block]
  (ffi/call "grease_call_void_block" :void :pointer block))
