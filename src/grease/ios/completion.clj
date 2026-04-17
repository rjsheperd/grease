(ns grease.ios.completion
  "Completion handlers as Clojure promises.

  [[with-completion]] wraps an ObjC block factory and returns a
  `[block-ptr promise]` pair.  Pass `block-ptr` to any iOS API that
  accepts a block-typed completion handler; the promise is delivered when
  iOS invokes the block.  The block retains itself and releases after delivery.

  [[let-completion]] is a syntactic convenience: it evaluates init-exprs
  that return promises, then deref's them all (with a timeout) before
  running the body.

  Common wrappers — [[fetch-url!]] and [[request-authorization!]] — cover
  the two most frequent async patterns and are themselves thin layers over
  [[with-completion]]."
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.blocks :as blk]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; with-completion (Phase 7.1)
;; =============================================================================

(defn with-completion
  "Returns `[block-ptr promise]` for the given `block-type`.

  `block-type` may be:
  - `:void`       — `void (^)(void)` — promise delivers `nil`
  - `:data`       — `void (^)(NSData*, NSURLResponse*, NSError*)` — delivers
                    `{:data d :response r :error e}`
  - `:bool-error` — `void (^)(BOOL, NSError*)` — delivers `{:ok? bool :error e}`

  The block retains itself in the registry and releases after it is invoked
  (or if it is never invoked it stays retained until [[grease.ios.retain/reset-all!]]).

  Pass `block-ptr` to the iOS API.  `deref` or `deref`-with-timeout the promise
  to receive the result."
  [block-type]
  (let [p   (promise)
        rk  (gensym "completion-block-")
        blk (case block-type
              :void
              (blk/make-void-block
               (fn []
                 (deliver p nil)
                 (retain/release! rk)))

              :data
              (blk/make-data-block
               (fn [data resp err]
                 (deliver p {:data data :response resp :error err})
                 (retain/release! rk)))

              :bool-error
              (blk/make-bool-error-block
               (fn [ok err]
                 (deliver p {:ok? (not= 0 (long ok)) :error err})
                 (retain/release! rk)))

              (throw (ex-info (str "Unknown completion block type: " block-type)
                              {:type block-type
                               :known [:void :data :bool-error]})))]
    (retain/retain! rk blk ::completion-block)
    [blk p]))

;; =============================================================================
;; let-completion macro (Phase 7.2)
;; =============================================================================

(defmacro let-completion
  "Binds each symbol to the value delivered by its promise expression.

  `bindings` alternates symbol and promise-expr pairs, following `let` syntax.
  All promise-exprs are evaluated first (starting any async operations), then
  all promises are deref'd with `timeout-ms` (default 30 000 ms).  Throws
  `ex-info` if any promise times out.

  Example:
    (let-completion [data (fetch-url! \"https://example.com\")
                     ok?  (request-authorization! :camera)]
      (process data ok?))"
  {:arglists '([bindings & body] [timeout-ms bindings & body])}
  [& args]
  (let [[timeout-ms bindings body-forms]
        (if (integer? (first args))
          [(first args) (second args) (nnext args)]
          [30000 (first args) (next args)])]
    (assert (even? (count bindings))
            "let-completion bindings must have an even number of forms")
    (let [pairs    (partition 2 bindings)
          syms     (mapv first pairs)
          exprs    (mapv second pairs)
          p-syms   (mapv #(gensym (str (name %) "-promise-")) syms)]
      `(let [~@(interleave p-syms exprs)
             ~@(mapcat (fn [sym p-sym]
                         [sym `(deref ~p-sym ~timeout-ms ::timeout)])
                       syms p-syms)]
         ~@(mapv (fn [sym]
                   `(when (= ::timeout ~sym)
                      (throw (ex-info ~(str "let-completion timed out waiting for " sym)
                                      {:sym '~sym :timeout-ms ~timeout-ms}))))
                 syms)
         ~@body-forms))))

;; =============================================================================
;; Common wrappers (Phase 7.3)
;; =============================================================================

(defn fetch-url!
  "Performs an HTTP GET for `url-string` and returns a promise.

  The promise delivers:
    `{:data <NSData pointer> :response <NSHTTPURLResponse pointer> :error <NSError or nil>}`

  The data task is started immediately on the shared NSURLSession.
  `deref` the promise to wait for the response."
  [url-string]
  (let [[blk p] (with-completion :data)
        session  (objc-rt/msg-send :pointer
                                   (grease/get-objc-class "NSURLSession")
                                   "sharedSession")
        url-obj  (f/string->nsurl url-string)
        req      (objc-rt/msg-send :pointer
                                   (grease/get-objc-class "NSURLRequest")
                                   "requestWithURL:" :pointer url-obj)
        task     (objc-rt/msg-send :pointer
                                   session
                                   "dataTaskWithRequest:completionHandler:"
                                   :pointer req :pointer blk)]
    (objc-rt/msg-send :void task "resume")
    p))

(defn request-authorization!
  "Requests media capture authorization for `media-type` (:camera or :microphone).

  Returns a promise that delivers a boolean (true = authorized).

  Example:
    (deref (request-authorization! :camera) 10000 false)"
  [media-type]
  (let [[blk p] (with-completion :bool-error)
        media-type-str (case media-type
                         :camera    "AVMediaTypeVideo"
                         :microphone "AVMediaTypeAudio"
                         (throw (ex-info (str "Unknown media type: " media-type)
                                         {:type media-type :known [:camera :microphone]})))
        media-ns  (f/->nsstring media-type-str)]
    (objc-rt/msg-send :void
                      (grease/get-objc-class "AVCaptureDevice")
                      "requestAccessForMediaType:completionHandler:"
                      :pointer media-ns :pointer blk)
    (delay (:ok? @p))))
