//
//  AppDelegate.swift
//  AppTemplate
//

import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        // Redirect stdout to the in-app log buffer before Clojure starts,
        // so all println output is captured for display in ContentView.
        bridge_setup_stdout_capture()

        // Boot the GraalVM isolate and run a quick sanity eval.
        let eval_result = call_eval("(str \"hello\" ((partial + 2) 40))")
        call_prn(eval_result)

        // Start the nREPL server on a background thread.
        DispatchQueue.global(qos: .background).async {
            call_start_server()
        }

        return true
    }

    // MARK: UISceneSession Lifecycle

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        return UISceneConfiguration(name: "Default Configuration",
                                    sessionRole: connectingSceneSession.role)
    }

    func application(_ application: UIApplication,
                     didDiscardSceneSessions sceneSessions: Set<UISceneSession>) {}
}
