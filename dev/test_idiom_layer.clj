;; dev/test_idiom_layer.clj — smoke test for the idiom layer on device
;;
;; Load and run from nREPL via scripts/repl-eval:
;;
;;   scripts/repl-eval "$(cat dev/test_idiom_layer.clj)"
;;
;; Or send it in chunks from your nREPL client. Each section is independent
;; and can be eval'd individually.
;;
;; The script exercises:
;;   Phase 0   — retain registry, color + font coercion
;;   Phase 4   — hiccup element creation
;;   Phase 8   — defscreen present! / dismiss!
;;   Phase 2   — KVO reaction
;;   Phase 3   — NSNotificationCenter (on/off)
;;   Phase 6.3 — hiccup :key / :constraints integration
;;   Phase 9   — UINavigationController-backed navigation
;;
;; Phases 1, 5, 6 (layout DSL), 7 require more wiring (delegates, animations,
;; layout-> macro) and are left for manual REPL exploration after this script runs.

(ns test-idiom-layer
  (:require [grease.ios.repl  :refer [on-main]]
            [grease.ios.uikit :as ui]))

;; Idiom layer is pre-loaded at startup — just alias into this ns.
(require '[grease.ios.retain     :as retain]
         '[grease.ios.color      :as color]
         '[grease.ios.font       :as font]
         '[grease.ios.hiccup     :as hiccup]
         '[grease.ios.screen     :as screen]
         '[grease.ios.kvo        :as kvo]
         '[grease.ios.notify     :as notify]
         '[grease.ios.nav        :as nav]
         '[grease.ios.objc       :as objc-rt]
         '[grease.ios.foundation :as f])

(println "[idiom] Namespaces aliased.")

;; =============================================================================
;; Phase 0 — retention registry + coercion
;; =============================================================================

(println "[idiom] Phase 0: retain registry + color/font coercion")

;; Retain a dummy value under a known key, then release it.
(let [k (retain/retain! ::smoke-test "dummy-value" ::test)]
  (assert (= "dummy-value" (retain/retained k)) "retain/retained works")
  (retain/release! k)
  (assert (nil? (retain/retained k)) "release! removes entry"))

(println "[idiom]   retain registry: OK")

;; UIColor coercion (pure data → ObjC pointer)
(on-main
 (let [c (color/->uicolor "#FF6600")]
   (assert (not (nil? c)) "->uicolor returns non-nil pointer")))

(println "[idiom]   ->uicolor: OK")

;; UIFont coercion
(on-main
 (let [f (font/->uifont ["Helvetica" 16])]
   (assert (not (nil? f)) "->uifont returns non-nil pointer")))

(println "[idiom]   ->uifont: OK")

;; =============================================================================
;; Phase 4 — hiccup element creation
;; =============================================================================

(println "[idiom] Phase 4: hiccup element creation")

(on-main
 (let [lbl (hiccup/create-element :label {:text "Idiom layer loaded!"})]
   (assert (not (nil? lbl)) "create-element :label returns pointer")
   ;; Add it to the root view so it's visible
   (let [root (ui/root-view)
         {:keys [w]} (ui/get-frame root)]
     (ui/set-frame! lbl 20.0 120.0 (- w 40.0) 44.0)
     (ui/add-subview! root lbl))))

(println "[idiom]   hiccup create-element: OK — label added to root view")

;; =============================================================================
;; Phase 8 — defscreen lifecycle
;; =============================================================================

(println "[idiom] Phase 8: defscreen present! / dismiss!")

(screen/defscreen IdiomTestScreen
  :state    {:count 0}
  :view     (fn [state]
              [:label {:text  (str "defscreen count: " (:count state))
                       :alpha 0.9}])
  :on-mount (fn [{:keys [state]}]
              (println "[idiom]   on-mount fired, state:" @state))
  :on-unmount (fn [{:keys [state]}]
                (println "[idiom]   on-unmount fired, state:" @state)))

(on-main
 (let [root (ui/root-view)
       {:keys [w]} (ui/get-frame root)
       ;; Use a sub-view as the screen's canvas so it doesn't replace the whole UI
       canvas (hiccup/create-element :view {:bg "#1a1a2e"})]
   (ui/set-frame! canvas 20.0 180.0 (- w 40.0) 80.0)
   (ui/add-subview! root canvas)
   (screen/present! IdiomTestScreen canvas)))

(println "[idiom]   defscreen present!: OK")

;; Update state and re-render (no Thread/sleep in SCI — just update immediately)
(swap! (screen/screen-state 'IdiomTestScreen) assoc :count 1)
(screen/update-view! 'IdiomTestScreen)
(println "[idiom]   update-view!: OK")

;; =============================================================================
;; Phase 2 — KVO reaction
;; =============================================================================

(println "[idiom] Phase 2: KVO reaction")

(let [a (atom 10)
      b (atom 20)
      sum (kvo/reaction (fn [] (+ @a @b)) a b)]
  (assert (= 30 @sum) "initial reaction value is 30")
  (swap! a + 5)
  (assert (= 35 @sum) "reaction updates when a changes"))

(println "[idiom]   kvo/reaction: OK")

;; =============================================================================
;; Phase 3 — NSNotificationCenter
;; =============================================================================

(println "[idiom] Phase 3: notify on/off")

(let [received (atom [])
      handle   (notify/on :keyboard-will-show
                           (fn [_info] (swap! received conj :keyboard-will-show)))]
  ;; Can't easily fire the keyboard notification from Clojure, but at least
  ;; verify the handle is returned and off! doesn't throw.
  (assert (some? handle) "on returns a handle")
  (notify/off handle)
  (println "[idiom]   notify on/off: OK (handle registered and released)"))

;; =============================================================================
;; Phase 6.3 — hiccup :key / :constraints integration
;; =============================================================================

(println "[idiom] Phase 6.3: hiccup :key / :constraints integration")

;; Mount a hiccup tree where the container carries :constraints and the child
;; carries a :key. apply-constraints! calls disable-autoresizing! on the child
;; and activates each NSLayoutConstraint — exercising the full parse + ObjC path.

(on-main
 (let [root        (ui/root-view)
       {:keys [w]} (ui/get-frame root)
       canvas      (hiccup/create-element :view {:bg "#1a2a1e"})]
   (ui/set-frame! canvas 20.0 280.0 (- w 40.0) 60.0)
   (ui/add-subview! root canvas)
   (hiccup/mount! ::constraint-smoke canvas []
                  (fn []
                    [:view {:bg         "#2a3a2e"
                            :constraints [[:eq :lbl :top    :parent :top     8.0]
                                          [:eq :lbl :left   :parent :left    8.0]
                                          [:eq :lbl :right  :parent :right  -8.0]
                                          [:eq :lbl :bottom :parent :bottom -8.0]]}
                     [:label {:key :lbl :text "Phase 6.3: constraints OK"}]]))))

(println "[idiom]   hiccup :key/:constraints: OK — label pinned inside canvas")

;; =============================================================================
;; Phase 9 — UINavigationController-backed navigation
;; =============================================================================

(println "[idiom] Phase 9: navigation (UINavigationController)")

(screen/defscreen NavSmokeRoot
  :state    {:n 0}
  :view     (fn [state]
              [:label {:text (str "Nav root  #" (:n state)) :alpha 1.0}])
  :on-mount (fn [_] (println "[idiom]   NavSmokeRoot viewDidLoad fired")))

(screen/defscreen NavSmokeDetail
  :state    {:n 1}
  :view     (fn [state]
              [:label {:text (str "Nav detail #" (:n state)) :alpha 1.0}])
  :on-mount (fn [_] (println "[idiom]   NavSmokeDetail viewDidLoad fired")))

;; Build a nav controller, push a second screen, then present it modally.
;; make-vc! registers each VC in nav-vc-registry synchronously (before
;; the UIKit push is dispatched), so the count assertions are reliable.

(on-main
 (let [reg-before (count @nav/nav-vc-registry)
       nc         (nav/make-nav! NavSmokeRoot)]
   (assert (some? nc) "make-nav! returns non-nil pointer")
   (assert (= (inc reg-before) (count @nav/nav-vc-registry))
           (str "registry grew by 1 after make-nav!, now: "
                (count @nav/nav-vc-registry)))
   (nav/push! nc NavSmokeDetail)
   (assert (= (+ 2 reg-before) (count @nav/nav-vc-registry))
           (str "registry grew by 2 after push!, now: "
                (count @nav/nav-vc-registry)))
   ;; Present the nav controller modally — user will see a full nav stack
   ;; (root + detail) slide up over the current UI.
   (let [rvc (objc-rt/msg-send :pointer (ui/key-window) "rootViewController")]
     (objc-rt/msg-send :void rvc
                       "presentViewController:animated:completion:"
                       :pointer nc
                       :int64   1
                       :pointer (f/null-ptr)))
   (println "[idiom]   nav make-nav! + push! + present: OK")))

(println "[idiom]   nav-vc-registry entries:" (count @nav/nav-vc-registry))
(println "[idiom]   dismiss the modal with:")
(println "[idiom]     (on-main (let [rvc (objc-rt/msg-send :pointer (ui/key-window) \"rootViewController\")]")
(println "[idiom]                (nav/dismiss-modal! rvc)))")

;; =============================================================================
;; Done
;; =============================================================================

(println "[idiom] All smoke tests passed.")
(println "[idiom] Check the screen for:")
(println "[idiom]   y=120  Phase 4 label")
(println "[idiom]   y=180  Phase 8 defscreen canvas (dark blue)")
(println "[idiom]   y=280  Phase 6.3 constraint canvas (dark green, label pinned)")
(println "[idiom]   modal  Phase 9 nav controller (root + detail screens)")
