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

    // MARK: - Async/await bridge

    /// Fetch a URL asynchronously and write the first 200 bytes of the
    /// response body into `message`.  ContentView displays it within 0.5 s.
    ///
    /// This demonstrates the @objc ↔ Swift async/await bridge pattern:
    /// Clojure calls a synchronous @objc entry point; Swift dispatches a Task
    /// internally; the result is written back to an @objc property that
    /// Clojure can read.
    ///
    /// From Clojure:
    ///   (objc-rt/msg-send :void hook "fetchURL:" :pointer (f/->nsstring "https://example.com"))
    ///   (deref (promise) 3000 nil)  ; wait for async result
    ///   (get-display-message)       ; => "HTTP 200: <!doctype html>..."
    @objc func fetchURL(_ urlString: String) {
        message = "Fetching \(urlString)..."
        Task {
            do {
                guard let url = URL(string: urlString) else {
                    message = "Error: invalid URL"
                    return
                }
                let (data, resp) = try await URLSession.shared.data(from: url)
                let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
                let preview = String(data: data.prefix(200), encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? "<binary>"
                message = "HTTP \(status): \(preview)"
            } catch {
                message = "Error: \(error.localizedDescription)"
            }
        }
    }
}
