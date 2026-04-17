(ns com.phronemophobic.grease
  (:require [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.struct :as dt-struct]
            [sci.core :as sci]
            [sci.addons :as addons]
            babashka.nrepl.server
            [clojure.java.io :as io]
            [com.phronemophobic.objcjure :as objcjure :refer [objc]]
            [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.scify :as scify])
  (:import org.graalvm.nativeimage.c.function.CEntryPointLiteral
           tech.v3.datatype.ffi.Pointer
           org.graalvm.word.WordBase
           java.net.NetworkInterface
           java.net.InetAddress)
  (:gen-class))

(set! *warn-on-reflection* true)

(extend-protocol dt-ffi/PToPointer
  CEntryPointLiteral
  (convertible-to-pointer? [item] true)
  (->pointer [item] (tech.v3.datatype.ffi.Pointer. (.rawValue (.getFunctionPointer item)))))

;; =============================================================================
;; Logging — println works from any GraalVM-attached thread and is captured
;; by Bridge.m's stdout pipe redirect into the on-screen log buffer.
;; =============================================================================

(defn ^:private nlog [msg]
  (println (str "[Clojure] " msg)))

;; =============================================================================
;; Build-time fingerprint — hash of grease.clj computed when native-image runs
;; (--initialize-at-build-time=com.phronemophobic fires this at compile time).
;; Stored as a plain long in the image; runtime reads are free.
;; =============================================================================

(def ^:private grease-hash-code
  (try
    (long (hash (slurp (io/resource "com/phronemophobic/grease.clj"))))
    (catch Exception _ 0)))

(defn clj_get_hash_code [_unused] grease-hash-code)

;; =============================================================================
;; Basic math / print entry points (keep for backwards compat with Bridge.m)
;; =============================================================================

(defn clj_sub [a b] (- a b))
(defn clj_add [a b] (+ a b))

(defn clj_print [bs]
  (prn (dt-ffi/c->string bs)))

(defn clj_print_hi []
  (nlog "hi from clj_print_hi"))

;; =============================================================================
;; Eval via SCI (called from Bridge.m)
;; =============================================================================

;; Captures the engine preload error (if any) so it can be read via nREPL.
;; Check with: @com.phronemophobic.grease/engine-load-error
(def engine-load-error (atom nil))

(def results (atom {:results {}
                    :id 0}))

(defn add-result [obj]
  (:id
   (swap! results
          (fn [{:keys [results id]}]
            (let [newid (inc id)]
              {:results (assoc results newid obj)
               :id newid})))))

(defn get-result [id]
  (get-in @results [:results id]))

(defn clj_prn [id]
  (prn (get-result id)))

;; Forward-declared -- sci-ctx is defined below after opts.
(declare sci-ctx)

(defn clj_eval [bs]
  (add-result (sci/eval-string* @sci-ctx (dt-ffi/c->string bs))))

;; =============================================================================
;; Network helpers
;; =============================================================================

(defn ^:private get-addresses
  "Returns all IPv4 addresses on en* interfaces."
  []
  (->> (NetworkInterface/getNetworkInterfaces)
       enumeration-seq
       (filter #(.startsWith (.getName ^NetworkInterface %) "en"))
       (mapcat #(enumeration-seq (.getInetAddresses ^NetworkInterface %)))
       (filter #(= 4 (count (.getAddress ^InetAddress %))))
       (filter #(.isSiteLocalAddress ^InetAddress %))
       (map #(.getHostAddress ^InetAddress %))))

;; =============================================================================
;; GCD dispatch helper
;; =============================================================================

(def ^:private main-queue
  (delay (ffi/dlsym ffi/RTLD_DEFAULT (dt-ffi/string->c "_dispatch_main_q"))))

(defn dispatch-main-async
  "Enqueues f (0-arity) on the main GCD queue asynchronously."
  [f]
  (ffi/call "dispatch_async" :void
            :pointer @main-queue
            :pointer (objc (fn ^void []
                             (try (f)
                                  (catch Exception e (println e)))))))

;; =============================================================================
;; ObjC runtime — class builder primitives
;;
;; ffi/call and clj-libffi.callback/make-callback use primitive type-hinted
;; dispatch that SCI cannot execute.  These thin wrappers are compiled into
;; the native image and exposed as sci/copy-var entries so that nREPL sessions
;; can define ObjC classes and method implementations at runtime.
;; =============================================================================

;; Prevent GC of live ffi_closure objects.  make-imp stores every closure here.
(def ^:private live-imps (atom []))

(defn get-objc-class
  "Returns the ObjC Class object for class-name, or nil if not found."
  [class-name]
  (ffi/call "objc_getClass" :pointer :pointer (dt-ffi/string->c class-name)))

(defn allocate-objc-class!
  "Allocates a new ObjC class pair (does not register it yet).
  superclass: pointer returned by get-objc-class."
  [^String class-name superclass]
  (ffi/call "objc_allocateClassPair" :pointer
            :pointer superclass :pointer (dt-ffi/string->c class-name) :int64 0))

(defn register-objc-class!
  "Finalises and registers a class created by allocate-objc-class!.
  No methods may be added after this call."
  [cls]
  (ffi/call "objc_registerClassPair" :void :pointer cls))

(defn register-objc-sel
  "Returns the SEL for selector-str, registering it if necessary."
  [^String selector-str]
  (ffi/call "sel_registerName" :pointer :pointer (dt-ffi/string->c selector-str)))

(defn add-objc-method!
  "Adds an IMP to cls for the given selector.
  sel: SEL pointer from register-objc-sel.
  imp: function pointer from make-imp.
  type-encoding: ObjC type encoding string e.g. \"v@:\" \"v@:@@\"."
  [cls sel imp ^String type-encoding]
  (ffi/call "class_addMethod" :int8
            :pointer cls :pointer sel :pointer imp :pointer (dt-ffi/string->c type-encoding)))

(defn ptr-address
  "Returns the native memory address of an ObjC pointer as a long.

  Implemented in JVM-compiled code so that the `^Pointer` type hint is
  resolved at compile time (direct field access, no reflection).  The
  SCI interpreter cannot resolve `tech.v3.datatype.ffi.Pointer` as a
  class name at eval time, so this must NOT live in SCI-interpreted code."
  ^long [ptr]
  (.address ^Pointer ptr))

(defn c->string
  "Converts a C string (char*) pointer to a Clojure string.
  JVM bridge for `tech.v3.datatype.ffi/c->string` — safe to call from SCI."
  [ptr]
  (dt-ffi/c->string ptr))

(defn string->c
  "Converts a Clojure string to a C string (char*) pointer.
  JVM bridge for `tech.v3.datatype.ffi/string->c` — safe to call from SCI."
  [s]
  (dt-ffi/string->c s))

(defn convertible-to-pointer?
  "Returns true if x implements `dt-ffi/PToPointer` (i.e. is already an ObjC pointer).
  JVM bridge — safe to call from SCI-interpreted code."
  [x]
  (dt-ffi/convertible-to-pointer? x))

(defn native-malloc
  "Allocates a GC-managed native memory buffer of `size` bytes.
  JVM bridge for `tech.v3.datatype.native-buffer/malloc` — safe to call from SCI."
  [size]
  (native-buffer/malloc size {:resource-type :gc}))

(defn native-write-long
  "Writes `long-val` at `byte-offset` in native buffer `nbuf`.
  JVM bridge for `tech.v3.datatype.native-buffer/write-long` — safe to call from SCI."
  [nbuf byte-offset long-val]
  (native-buffer/write-long nbuf byte-offset long-val))

(defn struct-inplace-new
  "Wraps native buffer `nbuf` as a struct of `type-kw`.
  JVM bridge for `tech.v3.datatype.struct/inplace-new-struct` — safe to call from SCI."
  [type-kw nbuf]
  (dt-struct/inplace-new-struct type-kw nbuf))

(defn struct-datatype?
  "Returns true if `kw` is a registered struct type in the dt-struct registry.
  JVM bridge for `tech.v3.datatype.struct/struct-datatype?` — safe to call from SCI."
  [kw]
  (dt-struct/struct-datatype? kw))

(defn struct-define-datatype!
  "Registers a struct layout in the dt-struct registry.
  JVM bridge for `tech.v3.datatype.struct/define-datatype!` — safe to call from SCI."
  [kw fields]
  (dt-struct/define-datatype! kw fields))

(defn objc-new
  "Sends +new to cls, returning the new instance pointer."
  [cls]
  (let [sel (register-objc-sel "new")]
    (ffi/call "objc_msgSend" :pointer :pointer cls :pointer sel)))

(defn make-imp
  "Wraps f in an FFI closure suitable for use as an ObjC method implementation.

  f receives (self _cmd & extra-args) as Clojure values (raw pointer longs).
  extra-arg-types: seq of dtype-next type keywords for any args beyond self/cmd
                   e.g. [] for a no-arg method, [:pointer :pointer] for two
                   object args.
  ret-type: dtype-next type keyword for the return, usually :void.

  The returned value is a Pointer to executable code; store it (or use
  live-imps) to prevent GC."
  [f extra-arg-types ret-type]
  ;; ObjC IMPs always receive self (id) and _cmd (SEL) as the first two args.
  ;; Use requiring-resolve to avoid top-level require of clj-libffi.callback,
  ;; which would capture stale native handles at build time under
  ;; --initialize-at-build-time=com.phronemophobic.
  (let [make-callback (requiring-resolve 'com.phronemophobic.clj-libffi.callback/make-callback)
        all-arg-types (into [:pointer :pointer] extra-arg-types)
        imp (make-callback f ret-type all-arg-types)]
    (swap! live-imps conj imp)
    imp))

;; =============================================================================
;; objc macro wrapper -- makes (objc [...]) SCI-context-aware
;; =============================================================================

(defmacro ^:private objc-wrapper [form]
  (binding [objcjure/*sci-ctx* @sci-ctx]
    (objcjure/objc-syntax &env form)))

;; =============================================================================
;; SCI context
;; =============================================================================

(def ^:private opts
  (-> {:classes {:allow :all
                 ;; Explicit registrations let SCI resolve class names in
                 ;; type hints and :import forms even when the namespace
                 ;; source is loaded lazily (e.g. tech.v3.datatype.ffi
                 ;; imports Pointer and Library in its own ns form).
                 'Pointer                      tech.v3.datatype.ffi.Pointer
                 'tech.v3.datatype.ffi.Pointer tech.v3.datatype.ffi.Pointer
                 'NativeBuffer                 tech.v3.datatype.native_buffer.NativeBuffer
                 'System              java.lang.System
                 'java.net.URL        java.net.URL
                 'ByteBuffer          java.nio.ByteBuffer
                 'java.nio.ByteBuffer java.nio.ByteBuffer
                 'ByteOrder           java.nio.ByteOrder
                 'java.nio.ByteOrder  java.nio.ByteOrder}
       :namespaces
       (merge
         ;; objcjure -- expose all public vars, rebind `objc` to SCI-aware wrapper
        (let [ns-map (scify/ns->ns-map 'com.phronemophobic.objcjure)
              sci-ns (-> ns-map first val first val meta :ns)]
          (assoc-in ns-map
                    ['com.phronemophobic.objcjure 'objc]
                    (sci/new-var 'objc @#'objc-wrapper
                                 (assoc (meta #'objcjure/objc) :ns sci-ns))))

         ;; clj-libffi -- ffi/call, ffi/dlsym, blocks, callbacks
        (scify/ns->ns-map 'com.phronemophobic.clj-libffi)
        (scify/ns->ns-map 'com.phronemophobic.clj-libffi.callback)

         ;; dtype FFI + struct helpers
        (scify/ns->ns-map 'tech.v3.datatype.ffi)
        (scify/ns->ns-map 'tech.v3.datatype.struct)
        (scify/ns->ns-map 'tech.v3.datatype.native-buffer)

         ;; grease.ios-host — JVM-side I/O helpers for engine namespace loading.
         ;; read-resource loads a classpath resource and returns its content as a
         ;; UTF-8 string, or nil if the resource does not exist.
         ;; Engine namespaces require this because clojure.java.io is not available
         ;; in the frozen GraalVM native-image SCI context.
        (let [host-ns (sci/create-ns 'grease.ios-host nil)]
          {'grease.ios-host
           {'read-resource
            (sci/new-var 'read-resource
                         (fn [path]
                           (when-let [url (io/resource path)]
                             (slurp url)))
                         {:ns host-ns})}})

         ;; grease namespace — dispatch-main-async, get-addresses, ObjC class builder
        (let [ns-name 'com.phronemophobic.grease
              sci-ns  (sci/create-ns ns-name nil)]
          {ns-name {'dispatch-main-async  (sci/copy-var dispatch-main-async sci-ns)
                    'get-addresses        (sci/copy-var get-addresses sci-ns)
                    'get-objc-class       (sci/copy-var get-objc-class sci-ns)
                    'allocate-objc-class! (sci/copy-var allocate-objc-class! sci-ns)
                    'register-objc-class! (sci/copy-var register-objc-class! sci-ns)
                    'register-objc-sel    (sci/copy-var register-objc-sel sci-ns)
                    'add-objc-method!     (sci/copy-var add-objc-method! sci-ns)
                    'ptr-address               (sci/copy-var ptr-address sci-ns)
                    'c->string                 (sci/copy-var c->string sci-ns)
                    'string->c                 (sci/copy-var string->c sci-ns)
                    'convertible-to-pointer?   (sci/copy-var convertible-to-pointer? sci-ns)
                    'native-malloc             (sci/copy-var native-malloc sci-ns)
                    'native-write-long         (sci/copy-var native-write-long sci-ns)
                    'struct-inplace-new        (sci/copy-var struct-inplace-new sci-ns)
                    'struct-datatype?          (sci/copy-var struct-datatype? sci-ns)
                    'struct-define-datatype!   (sci/copy-var struct-define-datatype! sci-ns)
                    'objc-new             (sci/copy-var objc-new sci-ns)
                    'make-imp             (sci/copy-var make-imp sci-ns)
                    'live-imps            (sci/copy-var live-imps sci-ns)
                    'engine-load-error    (sci/copy-var engine-load-error sci-ns)}}))}
      addons/future))

(def ^:private sci-ctx
  (delay
    (let [ctx (sci/init opts)]
      (sci/alter-var-root sci/out (constantly *out*))
      (sci/alter-var-root sci/err (constantly *err*))
      ;; Pre-load grease.ios.* so they are available from nREPL without rebuild.
      ;; retain must come first — foundation.clj requires it.
      (when-let [src (io/resource "grease/ios/retain.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/objc.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/foundation.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/blocks.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/repl.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/uikit.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/bluetooth.clj")]
        (sci/eval-string* ctx (slurp src)))
      (when-let [src (io/resource "grease/ios/camera.clj")]
        (sci/eval-string* ctx (slurp src)))
      ;; Idiom layer — coercion, hiccup, screen, KVO, etc.
      ;; retain is already loaded above; load the rest in dependency order.
      ;; Wrapped in try-catch so a load error doesn't block the nREPL.
      ;; Idiom layer (part 1) — no dependency on engine/invoke.
      (try
        (doseq [path ["grease/ios/color.clj"
                      "grease/ios/font.clj"
                      "grease/ios/delegate.clj"
                      "grease/ios/kvo.clj"
                      "grease/ios/notify.clj"
                      "grease/ios/completion.clj"]]
          (if-let [src (io/resource path)]
            (sci/eval-string* ctx (slurp src))
            (nlog (str "  [idiom] WARNING: resource not found: " path))))
        (nlog "  [idiom] Phase 1 loaded (color/font/delegate/kvo/notify/completion).")
        (catch Exception e
          (nlog (str "  [idiom] WARNING: phase 1 preload failed: " (.getMessage e)))))
      ;; Engine — data-driven API (dependency order: types first, api last).
      ;; Wrapped in try-catch so that a load error is logged but does not
      ;; prevent the nREPL from starting.
      (try
        (doseq [path ["grease/ios/structs.clj"
                      "grease/ios/types.clj"
                      "grease/ios/naming.clj"
                      "grease/ios/spec.clj"
                      "grease/ios/registry.clj"
                      "grease/ios/patterns.clj"
                      "grease/ios/invoke.clj"
                      "grease/ios/coerce.clj"
                      "grease/ios/api.clj"]]
          (if-let [src (io/resource path)]
            (sci/eval-string* ctx (slurp src))
            (nlog (str "  [engine] WARNING: resource not found: " path))))
        ;; Initialise naming + types + registry in dependency order via the public API.
        ;; This makes ios/call available immediately without a manual (api/load!).
        (sci/eval-string* ctx "(grease.ios.api/load!)")
        (nlog "  [engine] API loaded — grease.ios.api/call is ready")
        (catch Exception e
          (reset! engine-load-error {:message (.getMessage e) :ex (str e)})
          (nlog (str "  [engine] WARNING: engine preload failed: " (.getMessage e)))))
      ;; Idiom layer (part 2) — layout/hiccup/anim/screen/nav depend on engine/invoke.
      ;; layout must load before hiccup (hiccup requires grease.ios.layout).
      ;; nav must load after screen (nav requires grease.ios.hiccup + screen spec shape).
      (try
        (doseq [path ["grease/ios/layout.clj"
                      "grease/ios/hiccup.clj"
                      "grease/ios/anim.clj"
                      "grease/ios/screen.clj"
                      "grease/ios/nav.clj"]]
          (if-let [src (io/resource path)]
            (sci/eval-string* ctx (slurp src))
            (nlog (str "  [idiom] WARNING: resource not found: " path))))
        (nlog "  [idiom] Phase 2 loaded (hiccup/anim/layout/screen).")
        (catch Exception e
          (nlog (str "  [idiom] WARNING: phase 2 preload failed: " (.getMessage e)))))
      ;; Override clojure.core/load-file to support http/https URLs in addition to
      ;; classpath resources. The fetch runs on the JVM side (not SCI), so full
      ;; java.net networking is available. From the nREPL:
      ;;   (load-file "https://example.com/my-app.clj")
      ;;   (load-file "grease/ios/my-helper.clj")  ; classpath fallback
      (sci/intern ctx 'clojure.core 'load-file
                  (fn [path]
                    (let [source (if (re-matches #"https?://.*" path)
                                   (slurp (java.net.URL. path))
                                   (some-> (io/resource path) slurp))]
                      (some->> source (sci/eval-string* ctx)))))
      ctx)))

;; =============================================================================
;; nREPL server
;; =============================================================================

(def ^:private nrepl-port (atom 0))

(defn clj_nrepl_port [_unused] (long @nrepl-port))

(defn clj_start_server []
  (let [port 23456
        build-id (str (java.util.UUID/randomUUID))]
    (nlog (str "=== Clojure nREPL [" build-id "] ==="))
    (try
      (let [addrs (get-addresses)]
        (if (seq addrs)
          (doseq [addr addrs]
            (nlog (str "  Connect: " addr ":" port)))
          (nlog (str "  Connect: <device-ip>:" port))))
      (catch Exception e
        (nlog (str "  Warning: could not enumerate addresses: " (.getMessage e)))
        (nlog (str "  Connect: <device-ip>:" port))))
    (try
      (nlog "  Starting nREPL server...")
      (babashka.nrepl.server/start-server! @sci-ctx {:host "0.0.0.0" :port port})
      (reset! nrepl-port port)
      (nlog (str "  nREPL server ready on port " port))
      (catch Exception e
        (nlog (str "  nREPL start error: " e))))))

(defn clj_callback_fn []
  (println "hello callback"))

;; =============================================================================
;; native-image entry point
;; =============================================================================

(defn compile-interface-class
  ([] (compile-interface-class nil))
  ([_opts]
   ((requiring-resolve 'tech.v3.datatype.ffi.graalvm/expose-clojure-functions)
    {#'clj_sub          {:rettype :int64
                         :argtypes [['a :int64] ['b :int64]]}
     #'clj_add          {:rettype :int64
                         :argtypes [['a :int64] ['b :int64]]}
     #'clj_print        {:rettype :void
                         :argtypes [['bs :pointer]]}
     #'clj_prn          {:rettype :void
                         :argtypes [['bs :int64]]}
     #'clj_eval         {:rettype :int64
                         :argtypes [['bs :pointer]]}
     #'clj_start_server {:rettype :void
                         :argtypes []}
     #'clj_print_hi     {:rettype :void
                         :argtypes []}
     #'clj_callback_fn  {:rettype :void
                         :argtypes []}
     #'clj_get_hash_code {:rettype :int64
                          :argtypes [['unused :int64]]}
     #'clj_nrepl_port   {:rettype :int64
                         :argtypes [['unused :int64]]}}
    'com.phronemophobic.grease.interface nil)))

(when *compile-files*
  (compile-interface-class))
