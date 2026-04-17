(ns grease.ios.screen
  "Screen lifecycle management via defscreen, present!, and dismiss!.

  [[defscreen]] defines a named screen component with initial state and a
  view function.  [[present!]] initialises the state, renders the view tree
  into a root UIView, and calls any `:on-mount` hook.  [[dismiss!]] calls
  the `:on-unmount` hook and tears down retained resources.

  [[present!]] uses [[hiccup/mount!]] to reactively re-render the view
  tree whenever the screen's state atom changes.  [[dismiss!]] tears down
  watches and the rendered subtree via [[hiccup/unmount!]].

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
            [grease.ios.hiccup :as hiccup]))

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
  2. Calls `[[hiccup/mount!]]` to reactively render the view whenever state changes.
  3. Calls `(:on-mount screen-spec)` with `{:state <atom> :root-view <ptr>}`.
  4. Registers the screen in the active registry under its name.

  Returns the screen context map `{:name :state :root-view :view}`.
  Replaces any previously presented screen with the same name."
  [screen-spec root-view]
  (let [{:keys [name initial-state view on-mount]} screen-spec
        state (atom initial-state)
        ctx   {:name name :state state :root-view root-view
               :view view
               :on-unmount (:on-unmount screen-spec)}]
    (hiccup/mount! name root-view [state] #(view @state))
    (when on-mount
      (grease/dispatch-main-async
       #(on-mount {:state state :root-view root-view})))
    (swap! active-screens assoc name ctx)
    ctx))

(defn dismiss!
  "Tears down the screen registered under `screen-name`.

  1. Calls `(:on-unmount ctx)` with the context map if defined.
  2. Calls `[[hiccup/unmount!]]` to remove reactive watches and tear down the view tree.
  3. Removes the screen from the active registry.

  Safe to call if the screen is not currently presented."
  [screen-name]
  (when-let [ctx (get @active-screens screen-name)]
    (when-let [f (:on-unmount ctx)]
      (f ctx))
    (hiccup/unmount! screen-name)
    (swap! active-screens dissoc screen-name)))

(defn update-view!
  "Re-renders `screen-name`'s view tree with the current state.

  Useful for forcing an immediate re-render outside of the reactive cycle.
  Must be called on the main thread (or wraps in dispatch-main-async)."
  [screen-name]
  (when-let [{:keys [name state root-view view]} (get @active-screens screen-name)]
    (grease/dispatch-main-async
     #(hiccup/render! name root-view (view @state)))))

(defn screen-state
  "Returns the state atom for the currently presented `screen-name`, or nil."
  [screen-name]
  (get-in @active-screens [screen-name :state]))
