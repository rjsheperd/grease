(ns grease.ios.screen
  "Screen lifecycle management via defscreen, present!, and dismiss!.

  [[defscreen]] defines a named screen component with initial state and a
  view function.  [[present!]] initialises the state, renders the view tree
  into a root UIView, and calls any `:on-mount` hook.  [[dismiss!]] calls
  the `:on-unmount` hook and tears down retained resources.

  This is a v1 naive implementation: the view tree is rendered once and is
  not automatically updated when state changes.  Re-call [[present!]] on
  the same root view to force a re-render.

  Example:
    (defscreen Counter
      :state   {:count 0}
      :view    (fn [state]
                 [:label {:text (str \"Count: \" (:count state))
                          :align 1}])
      :on-mount (fn [{:keys [state]}]
                  (println \"Counter mounted, state:\" @state)))

    ;; Mount on device:
    (present! Counter root-view)"
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.hiccup :as hiccup]
            [grease.ios.retain :as retain]))

;; =============================================================================
;; Active screen registry
;; =============================================================================

(def ^:no-doc active-screens
  "Map of {screen-name → context-map} for all currently presented screens."
  (atom {}))

;; =============================================================================
;; defscreen macro (Phase 8.1)
;; =============================================================================

(defmacro defscreen
  "Defines a named screen component.

  Options (keyword + value pairs after `name`):
  - `:state`     — initial state value (any Clojure value)
  - `:view`      — `(fn [state] hiccup-tree)`, called with current state value
  - `:on-mount`  — `(fn [{:keys [state root-view]}] ...)`, called after first render
  - `:on-unmount` — `(fn [{:keys [state]}] ...)`, called before teardown

  The screen is defined as a plain map var; pass it to [[present!]] to mount.

  Example:
    (defscreen Greeting
      :state   {:name \"World\"}
      :view    (fn [state] [:label {:text (str \"Hello, \" (:name state))}]))"
  [screen-name & opts]
  (let [{:keys [state view on-mount on-unmount]} (apply hash-map opts)]
    `(def ~screen-name
       {:name         '~screen-name
        :initial-state ~state
        :view          ~view
        :on-mount      ~on-mount
        :on-unmount    ~on-unmount})))

;; =============================================================================
;; present! / dismiss! (Phase 8.2)
;; =============================================================================

(defn present!
  "Mounts `screen-spec` into `root-view`.

  1. Initialises a Clojure `atom` from `:initial-state`.
  2. Renders the hiccup tree returned by `(:view screen-spec)` via [[hiccup/render!]].
  3. Calls `(:on-mount screen-spec)` with `{:state <atom> :root-view <ptr>}`.
  4. Registers the screen in the active registry under its name.

  Returns the screen context map `{:name :state :root-view}`.
  Replaces any previously presented screen with the same name."
  [screen-spec root-view]
  (let [{:keys [name initial-state view on-mount]} screen-spec
        state (atom initial-state)
        ctx   {:name name :state state :root-view root-view
               :on-unmount (:on-unmount screen-spec)}]
    (grease/dispatch-main-async
     #(do
        (hiccup/render! name root-view (view @state))
        (when on-mount
          (on-mount {:state state :root-view root-view}))))
    (swap! active-screens assoc name ctx)
    ctx))

(defn dismiss!
  "Tears down the screen registered under `screen-name`.

  1. Calls `(:on-unmount ctx)` with the context map if defined.
  2. Releases hiccup views retained under the screen's render key.
  3. Removes the screen from the active registry.

  Safe to call if the screen is not currently presented."
  [screen-name]
  (when-let [ctx (get @active-screens screen-name)]
    (when-let [f (:on-unmount ctx)]
      (f ctx))
    (retain/release-kind! ::hiccup-view)
    (swap! active-screens dissoc screen-name)))

(defn update-view!
  "Re-renders `screen-name`'s view tree with the current state.

  Useful after manually updating the state atom to force a UI refresh.
  Must be called on the main thread (or wraps in dispatch-main-async)."
  [screen-name]
  (when-let [{:keys [name state root-view view]} (get @active-screens screen-name)]
    (grease/dispatch-main-async
     #(hiccup/render! name root-view (view @state)))))

(defn screen-state
  "Returns the state atom for the currently presented `screen-name`, or nil."
  [screen-name]
  (get-in @active-screens [screen-name :state]))
