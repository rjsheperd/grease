(ns com.phronemophobic.grease
  (:require [tech.v3.datatype.ffi :as dt-ffi]
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
                 'System       java.lang.System
                 'java.net.URL java.net.URL}
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

         ;; dtype FFI helpers
         (scify/ns->ns-map 'tech.v3.datatype.ffi)

         ;; grease namespace -- dispatch-main-async, get-addresses
         (let [ns-name 'com.phronemophobic.grease
               sci-ns  (sci/create-ns ns-name nil)]
           {ns-name {'dispatch-main-async (sci/copy-var dispatch-main-async sci-ns)
                     'get-addresses       (sci/copy-var get-addresses sci-ns)}}))}
      addons/future))

(def ^:private sci-ctx
  (delay
    (let [ctx (sci/init opts)]
      (sci/alter-var-root sci/out (constantly *out*))
      (sci/alter-var-root sci/err (constantly *err*))
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
