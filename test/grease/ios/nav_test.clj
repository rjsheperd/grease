(ns grease.ios.nav-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.nav :as nav]
            [grease.ios.retain :as retain]))

;; Tests cover the pure-Clojure parts of the navigation layer:
;; nav-vc-registry management and cleanup-vc! logic.
;;
;; ObjC-dependent paths (GrseNavVC class registration, viewDidLoad IMP,
;; pushViewController:animated:, etc.) are exercised on device.

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn- clear-registry! [f]
  (reset! nav/nav-vc-registry {})
  (f)
  (reset! nav/nav-vc-registry {}))

(use-fixtures :each clear-registry!)

;; ── nav-vc-registry atom ──────────────────────────────────────────────────────

(deftest ^:parallel registry-is-atom-test
  (testing "nav-vc-registry is an atom"
    (is (instance? clojure.lang.Atom nav/nav-vc-registry))))

(deftest ^:parallel registry-starts-empty-test
  (testing "nav-vc-registry is empty after fixture reset"
    (is (= {} @nav/nav-vc-registry))))

;; ── cleanup-vc! (pure — no ObjC calls) ───────────────────────────────────────

(defn- inject-vc!
  "Registers a fake VC entry without allocating a real ObjC object."
  [addr screen-spec]
  (let [state   (atom (:initial-state screen-spec))
        nav-key (gensym "nav-test-")
        rk      (retain/retain! nav-key :fake-ptr ::nav-vc)]
    (swap! nav/nav-vc-registry assoc addr
           {:screen-spec screen-spec
            :nav-key     nav-key
            :state       state
            :retain-key  rk})
    {:addr addr :state state :nav-key nav-key :rk rk}))

(deftest cleanup-vc-removes-registry-entry-test
  (testing "cleanup-vc! removes the entry from nav-vc-registry"
    (let [{:keys [addr]} (inject-vc! 0x1000 {:name 'S :initial-state {} :view identity})]
      (#'nav/cleanup-vc! addr)
      (is (nil? (get @nav/nav-vc-registry addr))))))

(deftest cleanup-vc-releases-retain-key-test
  (testing "cleanup-vc! calls retain/release! on the retain-key"
    (let [{:keys [addr rk]} (inject-vc! 0x2000 {:name 'S :initial-state {} :view identity})]
      (is (some? (retain/retained rk)) "entry present before cleanup")
      (#'nav/cleanup-vc! addr)
      (is (nil? (retain/retained rk)) "entry released after cleanup"))))

(deftest cleanup-vc-unmounts-hiccup-test
  (testing "cleanup-vc! calls hiccup/unmount! on the nav-key"
    (let [unmounted (atom nil)
          {:keys [addr nav-key]} (inject-vc! 0x3000
                                             {:name 'S :initial-state {} :view identity})]
      (with-redefs [hiccup/unmount! (fn [k] (reset! unmounted k))]
        (#'nav/cleanup-vc! addr))
      (is (= nav-key @unmounted)))))

(deftest cleanup-vc-fires-on-unmount-hook-test
  (testing "cleanup-vc! calls :on-unmount with {:state <atom>}"
    (let [hook-calls (atom [])
          spec {:name       'S
                :initial-state {:x 1}
                :view       identity
                :on-unmount (fn [ctx] (swap! hook-calls conj ctx))}
          {:keys [addr state]} (inject-vc! 0x4000 spec)]
      (with-redefs [hiccup/unmount! (fn [_])]
        (#'nav/cleanup-vc! addr))
      (is (= 1 (count @hook-calls)))
      (is (= state (:state (first @hook-calls)))))))

(deftest cleanup-vc-nil-hook-safe-test
  (testing "cleanup-vc! does not throw when :on-unmount is nil"
    (inject-vc! 0x5000 {:name 'S :initial-state {} :view identity :on-unmount nil})
    (with-redefs [hiccup/unmount! (fn [_])]
      (#'nav/cleanup-vc! 0x5000))
    (is (nil? (get @nav/nav-vc-registry 0x5000)) "entry removed despite nil hook")))

(deftest cleanup-vc-idempotent-test
  (testing "cleanup-vc! is safe to call twice (second call is a no-op)"
    (let [calls (atom 0)
          {:keys [addr]} (inject-vc! 0x6000 {:name 'S :initial-state {} :view identity})]
      (with-redefs [hiccup/unmount! (fn [_] (swap! calls inc))]
        (#'nav/cleanup-vc! addr)
        (#'nav/cleanup-vc! addr))
      (is (= 1 @calls) "unmount! called exactly once"))))

(deftest cleanup-vc-unknown-addr-noop-test
  (testing "cleanup-vc! on an unregistered address is a no-op"
    (is (nil? (#'nav/cleanup-vc! 0xDEAD)))))

;; ── multiple VC registration ──────────────────────────────────────────────────

(deftest ^:parallel multiple-vcs-independent-test
  (testing "two injected VCs have independent state atoms and nav-keys"
    (let [{e1 :nav-key s1 :state} (inject-vc! 0xA001 {:name 'A :initial-state {:n 1} :view identity})
          {e2 :nav-key s2 :state} (inject-vc! 0xA002 {:name 'B :initial-state {:n 2} :view identity})]
      (is (not= e1 e2) "unique nav-keys")
      (is (not= s1 s2) "distinct state atoms")
      (is (= {:n 1} @s1))
      (is (= {:n 2} @s2)))))
