;; Swift interop from Clojure nREPL — @objc bridge pattern.
;;
;; Any Swift class that inherits NSObject (or is marked @objc) is visible in
;; the ObjC runtime and accessible from Clojure via get-objc-class + msg-send.
;;
;; Usage: connect to the on-device nREPL (port 23456) and eval forms below.

(ns ^{:clj-kondo/config {:linters {:unresolved-symbol {:level :off}}}}
 swift-bridge
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]))

;; =============================================================================
;; Reading Swift class metadata
;;
;; Swift classes inheriting NSObject appear in the ObjC runtime under their
;; mangled name: "ModuleName.ClassName" (e.g. "MobileTest.AppDelegate").
;; =============================================================================

(defn get-class
  "Returns the ObjC Class pointer for name, or nil."
  [name]
  (grease/get-objc-class name))

(comment
  ;; Swift AppDelegate — always present
  (get-class "MobileTest.AppDelegate")
  ;; => {:address 0x...}

  ;; Swift classes without @objc annotation are NOT visible:
  ;; (get-class "MobileTest.ContentView")  ;; => {:address 0} (nil)

  ;; GreaseHook — @objc class added to MobileTest project for REPL use
  (get-class "GreaseHook")
  ;; => {:address 0x...}
  )

;; =============================================================================
;; Listing methods on a Swift class
;; =============================================================================

(comment
  (require '[grease.ios.repl :as repl])

  ;; AppDelegate exposes 4 ObjC-visible methods
  (repl/methods-of (get-class "MobileTest.AppDelegate"))
  ;; => {:count 4
  ;;     :methods ("application:configurationForConnectingSceneSession:options:"
  ;;               "application:didDiscardSceneSessions:"
  ;;               "application:didFinishLaunchingWithOptions:"
  ;;               "init")}

  ;; GreaseHook exposes its @objc-annotated methods
  (repl/methods-of (get-class "GreaseHook"))
  ;; => {:count N :methods ("setMessage:" "getMessage" "message" "setMessage:" ...)}
  )

;; =============================================================================
;; Two-way Clojure ↔ Swift communication via GreaseHook
;;
;; GreaseHook is an @objc class added to the Xcode project:
;;
;;   @objc class GreaseHook: NSObject {
;;       @objc static let shared = GreaseHook()
;;       @objc var message: String = "No message yet"
;;       @objc func setMessage(_ text: String) { message = text }
;;       @objc func getMessage() -> String { return message }
;;   }
;;
;; ContentView polls GreaseHook.shared.message every 0.5 s and displays it.
;; =============================================================================

(defn grease-hook
  "Returns the GreaseHook singleton (equivalent to GreaseHook.shared in Swift)."
  []
  (objc-rt/msg-send :pointer (get-class "GreaseHook") "shared"))

(defn set-display-message!
  "Sends text to the GreaseHook singleton, updating ContentView's display.
  Must be called from any thread — GreaseHook.setMessage: is thread-safe."
  [text]
  (objc-rt/msg-send :void (grease-hook) "setMessage:"
                    :pointer (f/->nsstring text)))

(defn get-display-message
  "Returns the current message string from GreaseHook.shared."
  []
  ;; Use the auto-synthesized property getter ("message" selector)
  (let [ns-str (objc-rt/msg-send :pointer (grease-hook) "message")]
    (f/nsstring->str ns-str)))

(comment
  ;; Write to the on-screen display from the REPL:
  (set-display-message! "Hello from Clojure REPL!")

  ;; Read it back (round-trip through ObjC runtime):
  (get-display-message)
  ;; => "Hello from Clojure REPL!"

  ;; Drive it reactively with a Clojure atom:
  (def counter (atom 0))

  (add-watch counter :display
             (fn [_ _ _ new-val]
               (set-display-message! (str "Count: " new-val))))

  ;; Now each reset! updates the iOS screen:
  (swap! counter inc)   ; screen shows "Count: 1"
  (swap! counter inc)   ; screen shows "Count: 2"

  ;; Remove when done:
  (remove-watch counter :display))

;; =============================================================================
;; Calling Swift methods that return NSString
;; =============================================================================

(comment
  ;; @objc properties synthesize getter/setter selectors automatically.
  ;; The pointer is an NSString — use f/nsstring->str to decode it.
  (let [hook (grease-hook)
        ns-str (objc-rt/msg-send :pointer hook "message")]
    (f/nsstring->str ns-str))
  ;; => "No message yet"  (or whatever was set last)
  )

;; =============================================================================
;; Accessing Swift @objc properties via KVC
;;
;; ObjC properties synthesize getter/setter selectors:
;;   @objc var message: String  →  "message" / "setMessage:"
;;
;; For non-primitive return types (String → NSString*), use :pointer.
;; =============================================================================

(comment
  (let [hook (grease-hook)]
    ;; Read property via getter selector
    (f/nsstring->str (objc-rt/msg-send :pointer hook "message"))

    ;; Write property via setter selector
    (objc-rt/msg-send :void hook "setMessage:" :pointer (f/->nsstring "via setter"))))

;; =============================================================================
;; Calling Swift static methods
;;
;; Swift static methods and class properties on @objc classes are exposed as
;; ObjC class methods.  Send the message to the Class pointer, not an instance.
;; =============================================================================

(comment
  ;; Swift:  @objc static func greet(_ name: String) -> String
  ;; Clojure:
  (f/nsstring->str
   (objc-rt/msg-send :pointer (get-class "GreaseHook") "greet:"
                     :pointer (f/->nsstring "World")))
  ;; => "Hello, World!"
  )

;; =============================================================================
;; Protocol adoption — make a Clojure-defined delegate conform to a Swift protocol
;;
;; Swift protocols with @objc annotation are accessible as ObjC protocols.
;; Use objc-rt/add-protocol! after defclass! to declare conformance.
;; =============================================================================

(comment
  ;; If GreaseHook.swift declares:
  ;;   @objc protocol GreaseEventListener: AnyObject {
  ;;       func onEvent(_ name: String)
  ;;   }
  ;; Then from Clojure (repl ns already required above):
  (repl/defclass! MyListener "NSObject"
    "onEvent:" "v@:@"
    (fn [_self _cmd name-ptr]
      (println "Event:" (f/nsstring->str name-ptr))))

  (objc-rt/add-protocol! MyListener "GreaseEventListener")
  ;; => true

  ;; Register listener with hook (if hook has addListener: method):
  (objc-rt/msg-send :void (grease-hook) "addListener:"
                    :pointer (objc-rt/new-instance MyListener)))
