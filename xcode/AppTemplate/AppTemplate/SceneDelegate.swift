//
//  SceneDelegate.swift
//  AppTemplate
//
//  Created by Adrian Smith on 6/7/21.
//

import UIKit
import SwiftUI

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?


    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        // Use this method to optionally configure and attach the UIWindow `window` to the provided UIWindowScene `scene`.
        // If using a storyboard, the `window` property will automatically be initialized and attached to the scene.
        // This delegate does not imply the connecting scene or session are new (see `application:configurationForConnectingSceneSession` instead).

        // Create the SwiftUI view that provides the window contents.
        let contentView = ContentView()

        // Use a UIHostingController as window root view controller.
        if let windowScene = scene as? UIWindowScene {
            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = UIHostingController(rootView: contentView)
            self.window = window
            window.makeKeyAndVisible()
        }

        // Handle grease:// URLs that launched the app from a cold start.
        if !connectionOptions.urlContexts.isEmpty {
            handleURLContexts(connectionOptions.urlContexts)
        }
    }

    // Handle grease://load?url=<encoded-url> deep links.
    // iOS calls this when the app is already running and a grease:// URL is opened.
    // For cold-start opens, connectionOptions.urlContexts is non-empty and is
    // handled by the same helper below.
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        handleURLContexts(URLContexts)
    }

    private func handleURLContexts(_ contexts: Set<UIOpenURLContext>) {
        guard let url = contexts.first?.url,
              url.scheme == "grease",
              url.host == "load",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let rawTarget = components.queryItems?.first(where: { $0.name == "url" })?.value,
              let targetURL = URL(string: rawTarget) else { return }

        GreaseHook.shared.loadedAppURL = nil
        GreaseHook.shared.message = "Loading \(targetURL.lastPathComponent)…"

        Task {
            do {
                let (data, response) = try await URLSession.shared.data(from: targetURL)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard status == 200 else {
                    GreaseHook.shared.message = "Load failed: HTTP \(status)"
                    return
                }
                guard let source = String(data: data, encoding: .utf8) else {
                    GreaseHook.shared.message = "Load failed: response is not UTF-8"
                    return
                }
                GreaseHook.shared.loadedAppURL = targetURL.absoluteString
                GreaseHook.shared.message = "Running \(targetURL.lastPathComponent)…"
                // call_eval is synchronous; dispatch to a background queue so we
                // do not block the Swift concurrency thread pool for large evals.
                DispatchQueue.global(qos: .userInitiated).async {
                    _ = call_eval(source)
                    GreaseHook.shared.message = "Loaded: \(targetURL.lastPathComponent)"
                }
            } catch {
                GreaseHook.shared.message = "Load failed: \(error.localizedDescription)"
            }
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        // Called as the scene is being released by the system.
        // This occurs shortly after the scene enters the background, or when its session is discarded.
        // Release any resources associated with this scene that can be re-created the next time the scene connects.
        // The scene may re-connect later, as its session was not necessarily discarded (see `application:didDiscardSceneSessions` instead).
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        // Called when the scene has moved from an inactive state to an active state.
        // Use this method to restart any tasks that were paused (or not yet started) when the scene was inactive.
    }

    func sceneWillResignActive(_ scene: UIScene) {
        // Called when the scene will move from an active state to an inactive state.
        // This may occur due to temporary interruptions (ex. an incoming phone call).
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        // Called as the scene transitions from the background to the foreground.
        // Use this method to undo the changes made on entering the background.
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        // Called as the scene transitions from the foreground to the background.
        // Use this method to save data, release shared resources, and store enough scene-specific state information
        // to restore the scene back to its current state.
    }


}

