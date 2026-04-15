//
//  GreaseHook.swift
//  MobileTest
//
//  @objc bridge class for Clojure ↔ Swift two-way communication.
//
//  Any Swift class inheriting NSObject is accessible from the Clojure nREPL
//  via the ObjC runtime using get-objc-class and msg-send.  This class
//  provides a singleton entry point that ContentView polls every 0.5 s.
//
//  From Clojure nREPL:
//    (def hook (objc-rt/msg-send :pointer (grease/get-objc-class "GreaseHook") "shared"))
//    (objc-rt/msg-send :void hook "setMessage:" :pointer (f/->nsstring "Hello!"))

import Foundation

// @objc(GreaseHook) pins the ObjC runtime name to "GreaseHook" (not "MobileTest.GreaseHook")
// so Clojure can look it up via (get-objc-class "GreaseHook").
@objc(GreaseHook) class GreaseHook: NSObject {

    // MARK: - Singleton

    @objc static let shared = GreaseHook()

    private override init() {
        super.init()
    }

    // MARK: - REPL-driven display message

    /// The last message set by a Clojure nREPL session.
    /// ContentView polls this via the `shared` singleton every 0.5 s.
    ///
    /// From Clojure — set:  (objc-rt/msg-send :void hook "setMessage:" :pointer (f/->nsstring "Hi!"))
    /// From Clojure — get:  (f/nsstring->str (objc-rt/msg-send :pointer hook "message"))
    @objc var message: String = "No message yet"
}
