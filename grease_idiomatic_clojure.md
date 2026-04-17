# Grease Idiom Layer — Design & Implementation Plan

**Audience:** Claude Code, working inside the existing `grease` repo.
**Depends on:** the data-driven engine from `grease-data-driven-architecture.md`, but most phases can begin before the engine is complete. Dependencies are noted explicitly.
**Constraint:** every macro must be SCI-compatible inside the frozen native-image. No nested backticks. Fully-qualified symbols inside syntax-quote. No `core.async`. No `clojure.spec.alpha` at runtime.

---

## 0. Preamble: What This Layer Is

The engine gives you `(ios/call obj :method args)`. Faithful, correct, and painful for anything beyond exploratory REPL use. This layer maps every recurring iOS pattern to the Clojure idiom that already solves the same problem. The binding table:

| iOS pattern | Clojure idiom | Why this mapping |
|---|---|---|
| Delegate + mutable state | `agent` | Serialized async state transitions from off-thread callbacks |
| KVO observation | `add-watch` on a synthetic ref | Exactly the same contract: notify on state change |
| View tree built with `addSubview:` | Hiccup vectors | Declarative tree structure → imperative mutations is Reagent's solved problem |
| Completion handler `(result, error)` | `promise` with `deref` + timeout | Same shape: one-shot async result delivery |
| NSNotificationCenter | Multimethod event bus | Pub/sub with keyword dispatch keys |
| Auto Layout constraints | Constraint EDN | Relationships between named views as data |
| UIView animation blocks | Threading macro | Record-then-execute pattern, composable sequences |
| `@autoreleasepool { ... }` | `with-open`-shaped macro | Scoped resource cleanup |
| Target/action for controls | `:on-tap` keyword in hiccup props | Event handler as data |

Every layer exposes the layer below it. A user who dislikes hiccup can call `(ios/call ...)`. A user who dislikes `defdelegate` can call `(objc-rt/defclass! ...)`. No escape hatches needed because the abstractions don't hide their foundation.

---

## 1. Inventory of Existing Patterns to Absorb

Before building anything new, catalog what the existing hand-written wrappers do. Every pattern below must be expressible in the idiom layer, or the layer is incomplete.

### 1.1 Patterns in `grease.ios.bluetooth`

- **State atom** (`bt-state`): mutable keyword derived from an ObjC enum integer via a lookup map (`state-keywords`). Updated inside a delegate callback.
- **Collection atom** (`peripherals`): `swap! conj` inside a delegate callback. Each entry is a Clojure map extracted from ObjC pointers.
- **Delegate with multiple methods**: `defclass!` with 3 method implementations, each touching different atoms.
- **Lifecycle**: `start-scanning!` creates manager + delegate, wires them, retains both in atoms. `stop-scanning!` sends a message. No teardown of the delegate or manager — they live forever.
- **Init pattern**: `alloc` then `initWithDelegate:queue:`, not `new`. The manager's init *takes* the delegate — it's not set afterward.
- **Main-thread requirement**: documented but not enforced in code.

### 1.2 Patterns in `grease.ios.camera`

- **Authorization flow**: class method → enum integer → keyword lookup. Then a `make-bool-error-block` completion handler.
- **Delegate with drop-frame handler**: two methods on one delegate, one does work, one is a no-op sentinel.
- **Session lifecycle**: `beginConfiguration` / `addInput:` / `addOutput:` / `commitConfiguration` / `startRunning` — a multi-step initialization that must happen atomically.
- **Queue creation**: raw `dispatch_queue_create` via `ffi/call`. The delegate's sample buffer callbacks arrive on this queue, not main.
- **ARC retention**: session, output, and delegate all held in separate atoms.

### 1.3 Patterns in `grease.ios.uikit`

- **View creation**: `new` then a series of setter calls (`setText:`, `setFont:`, `setBackgroundColor:`).
- **Struct access via C shims**: `grease_get_frame_x`, `grease_set_frame` etc. Structs (CGRect, CGPoint, CGSize) cannot be passed/returned through `objc_msgSend` on arm64 — the existing code decomposes them into scalar doubles via Bridge.m C functions.
- **View hierarchy traversal**: recursive walk producing nested Clojure maps.
- **Color creation**: `colorWithRed:green:blue:alpha:` via `msg-send` (returns a pointer, no struct issue).

### 1.4 Patterns in `grease.ios.foundation`

- **Type coercion**: bidirectional converters for NSString, NSNumber, NSArray, NSDictionary, NSError.
- **Null pointer workaround**: `clj-libffi` rejects Java `nil` and Clojure `nil` for `:pointer` args. `null-ptr` and `main-queue` return valid pointer objects for these cases.
- **Private `msg-send*`**: duplicates `objc-rt/msg-send` to avoid circular dependency.

### 1.5 Patterns in `grease.ios.blocks`

- **Block factory**: three typed variants (`void`, `data`, `bool-error`), each requiring a matching C shim in Bridge.m.
- **Retention**: `live-blocks` atom prevents GC of ffi_closure objects.
- **New block shapes require new C shims**: adding a block type (e.g., `void (^)(CMTime)` for AVFoundation seek completion) means editing Bridge.m, adding a C function, and adding a Clojure wrapper. This is a **slow-loop operation** — it requires a rebuild.

### 1.6 What's missing (patterns NOT in any existing wrapper)

- KVO observation (no wrapper uses `addObserver:forKeyPath:options:context:`)
- NSNotificationCenter subscription
- Auto Layout constraints
- UIView animations (the `make-void-block` exists but no animation wrapper uses it)
- Target/action for UIControl events
- UINavigationController / screen transitions
- UITableView / UICollectionView with data sources
- UIAlertController
- Timer / NSTimer
- Gesture recognizers

These are in scope for the idiom layer.

---

## 2. C Shim Budget

A critical constraint that shapes the entire build plan: **any feature that needs a new C shim in Bridge.m requires a full native-image rebuild (~90s + Xcode link + deploy).** The idiom layer should minimize new shims and batch the ones it needs.

### 2.1 Shims the idiom layer will need

| Shim | Why | Used by |
|---|---|---|
| `grease_animate` | `UIView animateWithDuration:animations:completion:` takes two blocks and a duration; wrapping in one C call avoids three separate `msg-send` + block-factory calls | Animations (Phase 5) |
| `grease_animate_spring` | `UIView animateWithDuration:delay:usingSpringWithDamping:...` — six parameters | Animations (Phase 5) |
| `grease_get_bounds_*` / `grease_set_bounds` | Same struct issue as frame | Hiccup reconciler (Phase 4) |
| `grease_get_transform` / `grease_set_transform` | CGAffineTransform is a struct | Animations (Phase 5) |
| `grease_get_alpha` / `grease_set_alpha` | Alpha is a CGFloat property — actually fine via `msg-send :float64`. **No shim needed.** | — |
| `grease_add_constraint` | `NSLayoutConstraint constraintWithItem:...` has 8 args. Actually fine via `msg-send` — no structs. **No shim needed.** | — |
| `grease_add_target_action` | `addTarget:action:forControlEvents:` is fine via `msg-send`. **No shim needed.** | — |
| `grease_make_completion_block` | Generic `void (^)(void)` — already exists as `grease_make_void_block`. **No new shim.** | — |
| `grease_make_animation_block` | Same as void block. **No new shim.** | — |
| `grease_get_safe_area_*` | `safeAreaInsets` returns `UIEdgeInsets` (struct). Need 4 scalar getters. | Layout (Phase 6) |
| `grease_get_content_size_*` | `contentSize` returns CGSize (struct). Need 2 scalar getters. | Scroll views |
| `grease_get_content_offset_*` | `contentOffset` returns CGPoint (struct). Need 2 scalar getters + 1 setter. | Scroll views |

### 2.2 Batching strategy

Add **all** needed shims to Bridge.m in a single commit before starting Phase 4. This is one slow-loop rebuild that unlocks all subsequent phases in the fast loop. Estimated: ~25 new C functions, ~200 lines of Objective-C. Half a day of work.

The shim file itself should be factored out of Bridge.m into a separate `GreaseShims.m` to keep Bridge.m from growing indefinitely. Include it in the Xcode project's compile sources.

---

## Phase 0: Foundations (no engine dependency)

These can be built today against the existing codebase, tested in pure JVM.

### Phase 0.1: Retention Registry

**Problem:** every existing wrapper manually manages ARC retention with ad-hoc atoms (`held-mgr`, `held-delegate`, `delegate-atom`, `session-atom`, `output-atom`, `live-blocks`, `live-imps`). The idiom layer will create ObjC objects prolifically — delegates per screen, observers per property, target-action handlers per button. Managing retention manually will not scale.

**Solution:** a single centralized retention table.

**File:** `src/grease/ios/retain.clj`

**Tasks:**

- **0.1.1** Define a private atom holding a map: `{<retain-key> {:ptr <objc-pointer> :kind <keyword> :created-at <epoch-ms>}}`.
- **0.1.2** Implement `(retain! key ptr kind)` — stores ptr. Key is caller-chosen (a gensym, a hash, a path — whatever the caller finds meaningful). Returns the key.
- **0.1.3** Implement `(release! key)` — removes from map, returns the ptr (caller may need to send a final message like `removeObserver:` before letting ARC collect). Returns nil if key absent.
- **0.1.4** Implement `(release-kind! kind)` — release all entries of a given `:kind`. Used for bulk teardown (e.g., release all `:delegate` entries for a screen).
- **0.1.5** Implement `(retained key)` — lookup, returns ptr or nil.
- **0.1.6** Implement `(retained-keys)` and `(retained-count)` — introspection for REPL debugging.
- **0.1.7** Implement `(reset-all!)` — clear everything. Emergency escape hatch.
- **0.1.8** Write tests: retain/release round-trip, release-kind selectivity, double-release is noop, reset clears.

**LOC estimate:** ~60.

### Phase 0.2: UIColor Coercion

**Problem:** the existing `uikit.clj` creates colors inline with `colorWithRed:green:blue:alpha:` and four explicit doubles. The hiccup layer needs a coercer that accepts many input shapes.

**File:** `src/grease/ios/color.clj`

**Tasks:**

- **0.2.1** Implement `(->uicolor spec)` where `spec` can be:
  - A keyword for semantic/system colors: `:white`, `:black`, `:red`, `:label`, `:system-background`, `:separator`, etc. These map to `UIColor` class methods (`whiteColor`, `blackColor`, `labelColor`, `systemBackgroundColor`).
  - A vector `[r g b]` or `[r g b a]` with 0.0–1.0 doubles.
  - A hex string `"#FF5733"` or `"#FF5733AA"`.
  - An existing pointer (returned as-is — idempotent).
- **0.2.2** Build the semantic color keyword → selector lookup table as EDN (`resources/grease/colors.edn`):
  ```clojure
  {:white "whiteColor" :black "blackColor" :clear "clearColor"
   :red "redColor" :blue "blueColor" :green "greenColor"
   :label "labelColor" :secondary-label "secondaryLabelColor"
   :system-background "systemBackgroundColor"
   :secondary-system-background "secondarySystemBackgroundColor"
   :separator "separatorColor" :link "linkColor"
   ;; ... all UIColor semantic class methods
   }
  ```
- **0.2.3** Implement hex-string parser (pure Clojure, no iOS dependency — testable in JVM).
- **0.2.4** Write tests: keyword dispatch, RGBA vector, hex string parsing, idempotent pointer passthrough. Semantic colors need `^:integration` tag (they call `msg-send`).

**LOC estimate:** ~80 + EDN table.

### Phase 0.3: UIFont Coercion

**Problem:** same as color — the hiccup layer needs a multi-shape coercer.

**File:** `src/grease/ios/font.clj`

**Tasks:**

- **0.3.1** Implement `(->uifont spec)` where `spec` can be:
  - A number: `16` → system font at size 16.
  - A vector `[:system 16]`, `[:system-bold 16]`, `[:system-italic 16]`, `[:monospaced 14]`.
  - A vector `["Helvetica Neue" 14]` → `fontWithName:size:`.
  - An existing pointer (returned as-is).
- **0.3.2** Build the font-keyword → class-method lookup as a Clojure map (not EDN — small enough to inline):
  ```clojure
  {:system           "systemFontOfSize:"
   :system-bold      "boldSystemFontOfSize:"
   :system-italic    "italicSystemFontOfSize:"
   :monospaced       "monospacedSystemFontOfSize:weight:"  ; needs extra weight arg
   }
  ```
- **0.3.3** Write tests: all input shapes. Integration tests for actual UIFont creation.

**LOC estimate:** ~50.

### Phase 0.4: `with-autorelease`

**Problem:** tight loops that create ObjC temporaries leak unless wrapped in an autorelease pool. Currently no helper exists.

**File:** add to `grease.ios.foundation` (or a new `grease.ios.mem.clj` if you prefer separation).

**Tasks:**

- **0.4.1** Implement the macro:
  ```clojure
  (defmacro with-autorelease [& body]
    `(let [pool# (com.phronemophobic.clj-libffi/call
                   "objc_autoreleasePoolPush" :pointer)]
       (try ~@body
         (finally
           (com.phronemophobic.clj-libffi/call
             "objc_autoreleasePoolPop" :void :pointer pool#)))))
  ```
- **0.4.2** Verify SCI compatibility: no nested backticks, fully-qualified symbols.
- **0.4.3** Write test: create 1000 NSStrings inside `with-autorelease`, verify no crash. Tag `^:integration`.

**LOC estimate:** ~15.

### Phase 0.5: `with-retained`

**Problem:** the `with-open` analog for ObjC objects — retain for the block, release after.

**Tasks:**

- **0.5.1** Implement:
  ```clojure
  (defmacro with-retained [bindings & body]
    ;; bindings: [sym1 (make-something) sym2 (make-other)]
    ;; Retains each value in the registry, executes body, releases all on exit.
    ...)
  ```
  Each binding form is evaluated, the result retained via `retain!`, bound to the symbol, and released in a `finally` block.
- **0.5.2** SCI compatibility check.
- **0.5.3** Tests: verify release fires on normal exit and on exception.

**LOC estimate:** ~30.

---

## Phase 1: Delegates as Agents

**Depends on:** Phase 0.1 (retention registry).
**Does NOT depend on:** the data-driven engine. Uses `objc-rt/defclass!` directly.

### Problem Recap

Every existing delegate wrapper (`bluetooth.clj`, `camera.clj`, `dev/test_location.clj`) follows the same pattern:

1. Define N atoms for state.
2. `defclass!` with N methods, each mutating atoms.
3. Instantiate the class, store in yet another atom for retention.
4. Wire to manager via `setDelegate:` or `initWithDelegate:`.
5. State is scattered across unrelated atoms with no unified view.

Agents solve this: one agent holds the entire delegate's state, callbacks dispatch into the agent's executor (serialized), and the agent is watchable.

### File: `src/grease/ios/delegate.clj`

### Tasks:

- **1.1** Define the `defdelegate` macro's input shape:
  ```clojure
  (defdelegate <name> <protocol-name>
    {:state   {<initial-state-map>}
     :methods {<method-descriptor> <state-transition-fn>
               ...}})
  ```
  Where `<method-descriptor>` is a vector of keyword-ized selector parts and argument names:
  ```clojure
  [:location-manager mgr :did-update-locations locs]
  ;; maps to selector "locationManager:didUpdateLocations:"
  ;; with type encoding inferred or explicitly provided
  ```
  And `<state-transition-fn>` is `(fn [current-state & objc-args] -> new-state)`.

- **1.2** Implement selector reconstruction from keyword descriptors:
  - `[:location-manager _ :did-update-locations _]` → `"locationManager:didUpdateLocations:"`
  - `[:central-manager-did-update-state _]` → `"centralManagerDidUpdateState:"`
  - Pure function, testable in JVM. Write a translation table of every selector used in the existing wrappers and assert each reconstructs correctly.

- **1.3** Implement type-encoding inference from selector and protocol:
  - For v1: require explicit encoding in the method descriptor: `{:encoding "v@:@@"}`.
  - For v2 (after the engine exists): look up encoding from the framework spec EDN.
  - Fallback for unknown: all-pointer args, void return (`"v@:"` + N `"@"`s).

- **1.4** Implement the macro expansion:
  - Creates a factory function (not a class directly — the class is created *inside* the factory, versioned via `defclass!`).
  - The factory returns a Clojure agent wrapping `:state`, with metadata `{::objc-ptr <delegate-ptr> ::protocol <name>}`.
  - Each ObjC method IMP does: `(send agent transition-fn & coerced-args)`.
  - The delegate pointer is retained in the retention registry (Phase 0.1).
  - The factory is `def`'d as `<name>` in the calling namespace.

- **1.5** Implement `(objc-ptr agent)` — extract the ObjC delegate pointer from agent metadata. Used to wire the delegate: `(objc-rt/msg-send :void mgr "setDelegate:" :pointer (d/objc-ptr my-delegate))`.

- **1.6** Implement `(release-delegate! agent)` — remove from retention registry. Agent becomes a normal Clojure agent with no ObjC backing.

- **1.7** Implement protocol conformance: after `defclass!`, call `(objc-rt/add-protocol! cls protocol-name)` so `conformsToProtocol:` checks pass. Use the existing `add-protocol!` from `grease.ios.objc`.

- **1.8** Write the Bluetooth oracle test:
  Rewrite the `bluetooth.clj` BT delegate using `defdelegate`, assert that:
  - `@bt-delegate` returns the state map with `:bt-state` and `:peripherals` keys.
  - State transitions match what the existing wrapper produces for the same callback sequence.
  - `add-watch` on the delegate fires on state change.
  Tag `^:integration`.

- **1.9** Write the Location oracle test:
  Rewrite `dev/test_location.clj` using `defdelegate`, assert parity.
  Tag `^:integration`.

- **1.10** Write JVM-only unit tests using a mock `msg-send`:
  - Factory creates agent with correct initial state.
  - Simulated callback via direct function call updates state.
  - `objc-ptr` returns non-nil.
  - `release-delegate!` removes from registry.

**LOC estimate:** ~200 for the macro + helpers. ~150 for tests.

### Open design question: agent error mode

What happens when a transition function throws? Options:

- **`:continue`** (default for Clojure agents): agent enters error state, all subsequent `send`s are silently dropped until `restart-agent`. This is bad for delegates — a single bad callback would silently break all future callbacks.
- **Catch-and-log**: wrap every transition in `try/catch`, log the error, return the previous state unchanged. This is what the existing wrappers do implicitly (the atoms don't change if the callback throws).

**Recommendation:** catch-and-log, storing the last error in the agent's state under `::last-error`. The user can watch for it.

---

## Phase 2: KVO as `add-watch`

**Depends on:** Phase 0.1 (retention registry).
**Requires one new C shim:** none — `addObserver:forKeyPath:options:context:` and `removeObserver:forKeyPath:` are both pure `msg-send` calls with no struct args. However, the KVO notification callback arrives on the observer object, which means we need to `defclass!` an internal observer. This is a fast-loop operation.

### File: `src/grease/ios/kvo.clj`

### Tasks:

- **2.1** Define the internal KVO observer class:
  - `defclass!` a `GreaseKVOObserver` with one method: `observeValueForKeyPath:ofObject:change:context:` (`"v@:@@@@"`).
  - This class is created once at module load time. Its single method dispatches into a Clojure dispatch table (an atom mapping context-pointer → callback-fn).
  - The context pointer is a unique identifier per observation (use `(hash [obj key-path])` encoded as a long, or a counter).

- **2.2** Implement `(observe obj key-path)`:
  - Returns a custom type implementing `IDeref` and `clojure.lang.IRef` (for `add-watch`/`remove-watch`).
  - On creation:
    - Allocates a unique context ID.
    - Registers the context → callback mapping in the dispatch table.
    - Calls `(objc-rt/msg-send :void obj "addObserver:forKeyPath:options:context:" ...)`.
    - Options: `3` (= `NSKeyValueObservingOptionNew | NSKeyValueObservingOptionOld`).
    - Retains the observer in the retention registry.
  - `deref`: returns the current value atom's content.
  - `add-watch`: delegates to the internal current-value atom.
  - `remove-watch`: delegates to the internal current-value atom.

- **2.3** Implement `(release! observable)`:
  - Calls `removeObserver:forKeyPath:` on the original object.
  - Removes the context → callback from the dispatch table.
  - Releases from retention registry.
  - Notifies all watches with a final `nil` value (convention: nil means "observation ended").

- **2.4** Implement `(with-observers bindings body-fn)`:
  - Macro that sets up N observers, calls `body-fn` with a map of current values whenever any changes, and releases all on `.close`.
  - Returns an `AutoCloseable` for use with `with-open`.

- **2.5** Implement `(reaction expr-fn & observables)`:
  - Derived ref: re-evaluates `expr-fn` whenever any input observable changes.
  - Only fires watches when the *derived* value changes (not the input).
  - Returns same `IDeref`/`IRef` type.

- **2.6** Write JVM tests with mock:
  - Create a fake observable, simulate a KVO notification by directly calling the dispatch table, verify `deref` and `add-watch` fire.
  - Verify `release!` deregisters.
  - Verify `reaction` only fires on derived-value change.

- **2.7** Write integration test:
  - Create a UIView, observe its `alpha` property, change alpha via `msg-send`, verify the observer fires.
  - Tag `^:integration`.

**LOC estimate:** ~180. ~100 for tests.

### Design note: the context pointer trick

ObjC KVO delivers the context as a `void *`. We need to map this back to a Clojure callback. Options:

- **Counter-based**: maintain a global `(atom 0)` counter. Each observation gets the next integer. The dispatch table is `{42 callback-fn, 43 callback-fn, ...}`. The observer's IMP receives the context as an integer and looks it up. This is simplest.
- **Pointer-based**: store the callback fn in an atom, pass the atom's address as context. Fragile and unnecessary.

**Decision:** counter-based.

---

## Phase 3: Notifications as Event Bus

**Depends on:** Phase 0.1 (retention registry), Phase 1 (uses `defclass!` internally).

### File: `src/grease/ios/notify.clj`

### Tasks:

- **3.1** Build the notification name registry as EDN (`resources/grease/notifications.edn`):
  ```clojure
  {:keyboard-will-show
   {:objc "UIKeyboardWillShowNotification"
    :user-info {:frame-begin {:key "UIKeyboardFrameBeginUserInfoKey" :coerce :cg-rect}
                :frame-end   {:key "UIKeyboardFrameEndUserInfoKey"   :coerce :cg-rect}
                :duration    {:key "UIKeyboardAnimationDurationUserInfoKey" :coerce :double}
                :curve       {:key "UIKeyboardAnimationCurveUserInfoKey"    :coerce :long}}}
   :keyboard-will-hide
   {:objc "UIKeyboardWillHideNotification"
    :user-info {:duration {:key "UIKeyboardAnimationDurationUserInfoKey" :coerce :double}}}
   :app-did-enter-background
   {:objc "UIApplicationDidEnterBackgroundNotification" :user-info {}}
   :app-will-enter-foreground
   {:objc "UIApplicationWillEnterForegroundNotification" :user-info {}}
   ;; ... extend as needed
   }
  ```

- **3.2** Implement internal observer class:
  - One `defclass!` `GreaseNotificationObserver` with method `handleNotification:` (`"v@:@"`).
  - The method extracts the notification name, looks it up in a dispatch table atom, and calls every registered callback for that name.
  - The observer is created once at module load time and retained forever.

- **3.3** Implement `(on notification-key callback-fn)`:
  - Registers callback in the dispatch table.
  - If this is the first subscriber for `notification-key`, calls `NSNotificationCenter addObserver:selector:name:object:` to wire the internal observer.
  - Returns a subscription handle (opaque, used for `off`).

- **3.4** Implement `(off handle)`:
  - Removes callback from dispatch table.
  - If last subscriber for that notification, calls `removeObserver:name:object:`.

- **3.5** Implement `(once notification-key callback-fn)`:
  - Like `on`, but auto-calls `off` after the first invocation.

- **3.6** Implement user-info coercion:
  - Given a notification and its registry entry, extract the userInfo dict and coerce each declared key.
  - Uses `grease.ios.foundation/nsdict->map` for the raw dict, then applies per-key coercers.
  - `:cg-rect` coercion requires C shims (add to the Phase 2.2 shim batch). For v1, defer `:cg-rect` coercion and return the raw pointer with a TODO.

- **3.7** Write tests:
  - JVM: subscribe, simulate dispatch via direct function call, verify callback fires.
  - JVM: subscribe two, off one, verify only the remaining fires.
  - Integration: subscribe to `:app-did-enter-background`, background the app in the simulator, verify callback fires. Tag `^:integration`.

**LOC estimate:** ~120. EDN table grows over time.

---

## Phase 4: Hiccup for UIKit

**Depends on:** Phase 0.2 (color), Phase 0.3 (font), C shim batch (Phase 0 prerequisite).
**Does NOT depend on:** Phase 1 (delegates) or Phase 2 (KVO) for the naive version. Reactive bindings (Phase 4.3) depend on Phase 2.

This is the largest phase. Split into three sub-phases.

### Phase 4.1: Tag Registry and Element Creation

**File:** `src/grease/ios/hiccup.clj` (main), `resources/grease/hiccup.edn` (data)

**Tasks:**

- **4.1.1** Define the tag registry EDN:
  ```clojure
  {:tags
   {:view        {:class "UIView"}
    :label       {:class "UILabel"}
    :button      {:class "UIButton"
                  :factory "buttonWithType:" :factory-arg 0} ; UIButtonTypeSystem = 0
    :image       {:class "UIImageView"}
    :text-field  {:class "UITextField"}
    :text-view   {:class "UITextView"}
    :switch      {:class "UISwitch"}
    :slider      {:class "UISlider"}
    :progress    {:class "UIProgressView"}
    :stack       {:class "UIStackView"}
    :scroll      {:class "UIScrollView"}
    :activity    {:class "UIActivityIndicatorView"}
    :segmented   {:class "UISegmentedControl"}
    :page        {:class "UIPageControl"}
    :stepper     {:class "UIStepper"}
    :picker      {:class "UIPickerView"}
    :web         {:class "WKWebView"}
    :map         {:class "MKMapView"}}

   :props
   {;; Universal view properties
    :bg           {:sel "setBackgroundColor:"  :enc "v@:@" :coerce :ui-color}
    :alpha        {:sel "setAlpha:"            :enc "v@:d" :coerce :double :arg-type :float64}
    :hidden       {:sel "setHidden:"           :enc "v@:B" :coerce :bool   :arg-type :int8}
    :corner       {:sel "setCornerRadius:"     :enc "v@:d" :coerce :double :arg-type :float64
                   :target :layer}  ; send to view.layer, not view
    :clips        {:sel "setClipsToBounds:"    :enc "v@:B" :coerce :bool   :arg-type :int8}
    :tag          {:sel "setTag:"              :enc "v@:q" :coerce :long   :arg-type :int64}
    :opaque       {:sel "setOpaque:"           :enc "v@:B" :coerce :bool   :arg-type :int8}

    ;; Text properties (UILabel, UITextField, UITextView)
    :text         {:sel "setText:"             :enc "v@:@" :coerce :ns-string}
    :font         {:sel "setFont:"             :enc "v@:@" :coerce :ui-font}
    :color        {:sel "setTextColor:"        :enc "v@:@" :coerce :ui-color}
    :align        {:sel "setTextAlignment:"    :enc "v@:q" :coerce :text-alignment :arg-type :int64}
    :lines        {:sel "setNumberOfLines:"    :enc "v@:q" :coerce :long :arg-type :int64}

    ;; Button properties
    :title        {:sel "setTitle:forState:"   :enc "v@:@q" :coerce :ns-string :extra-args [0]}

    ;; Image properties
    :image-name   {:sel "setImage:"            :enc "v@:@" :coerce :ui-image-named}

    ;; Stack view properties
    :axis         {:sel "setAxis:"             :enc "v@:q" :coerce :stack-axis :arg-type :int64}
    :spacing      {:sel "setSpacing:"          :enc "v@:d" :coerce :double :arg-type :float64}
    :distribution {:sel "setDistribution:"     :enc "v@:q" :coerce :stack-distribution :arg-type :int64}
    :alignment    {:sel "setAlignment:"        :enc "v@:q" :coerce :stack-alignment :arg-type :int64}

    ;; Frame (special — uses C shim, not msg-send)
    :frame        {:special :set-frame}
    :center       {:special :set-center}}

   :coercions
   {:ui-color          grease.ios.color/->uicolor
    :ui-font           grease.ios.font/->uifont
    :ns-string         grease.ios.foundation/->nsstring
    :double            double
    :long              long
    :bool              #(if % 1 0)
    :text-alignment    {:left 0 :center 1 :right 2 :justified 3 :natural 4}
    :stack-axis        {:horizontal 0 :vertical 1}
    :stack-distribution {:fill 0 :fill-equally 1 :fill-proportionally 2 :equal-spacing 3 :equal-centering 4}
    :stack-alignment   {:fill 0 :leading 1 :top 1 :first-baseline 2 :center 3 :trailing 4 :bottom 4 :last-baseline 5}
    :ui-image-named    :special} ; handled in code — UIImage imageNamed:

   :events
   {:on-tap     {:control-event 64}   ; UIControlEventTouchUpInside = 1 << 6
    :on-change  {:control-event 4096} ; UIControlEventValueChanged = 1 << 12
    :on-edit    {:control-event 524288}}} ; UIControlEventEditingChanged
  ```

- **4.1.2** Implement `(create-element tag props)`:
  - Look up tag in registry → class name.
  - If `:factory` is present, use `msg-send` with the factory selector. Otherwise, use `grease/objc-new`.
  - For each prop in `props`:
    - If it's an event key (`:on-tap` etc.), defer to event wiring (4.1.4).
    - If it has `:special` handling (`:frame`, `:center`), call the corresponding C shim.
    - If it has `:target :layer`, get the layer first: `(objc-rt/msg-send :pointer view "layer")`, then send the setter to the layer.
    - If it has `:extra-args`, append those to the `msg-send` call.
    - Otherwise: coerce the value, call `msg-send` with the declared selector and arg types.
  - Retain the view in the registry.
  - Return the view pointer.

- **4.1.3** Implement coercion dispatch:
  - The `:coerce` key references either a function symbol (resolve it) or a keyword map (enum-style lookup).
  - The `:special` coercion for `:ui-image-named` calls `(msg-send :pointer (get-objc-class "UIImage") "imageNamed:" :pointer (->nsstring name))`.

- **4.1.4** Implement event wiring for `:on-tap`, `:on-change`, etc.:
  - Create a `defclass!` handler class (once, at module load) with a `handleEvent:` method (`"v@:@"`).
  - For each event prop, register the callback fn in a dispatch table keyed by `[view-ptr event-type]`.
  - Call `addTarget:action:forControlEvents:` on the view.
  - The handler's IMP looks up `[sender control-event]` in the dispatch table and calls the registered fn.

- **4.1.5** Write JVM tests with mock `msg-send`:
  - `(create-element :label {:text "Hi" :font [:system-bold 16] :color :red})` produces the expected sequence of `msg-send` calls.
  - Event props register in the dispatch table.
  - Unknown tags throw `ex-info`.
  - Unknown props throw `ex-info`.

- **4.1.6** Write integration test:
  - Create a label, add it to root view, screenshot, verify it's visible. Tag `^:integration`.

**LOC estimate:** ~300 for element creation. EDN table ~100 lines.

### Phase 4.2: Tree Rendering and Naive Reconciler

**File:** continues in `src/grease/ios/hiccup.clj`

**Tasks:**

- **4.2.1** Implement `(render-tree hiccup-vec)`:
  - Recursive. For each `[tag props & children]`:
    - Call `create-element` for the tag.
    - Recursively render each child.
    - Call `addSubview:` to attach each child to the parent.
    - Handle `for`/`map` results (flatten seqs of hiccup vectors).
  - Returns the root view pointer.

- **4.2.2** Implement `(render! root-key hiccup-vec)`:
  - Maintains a map `{root-key {:tree <previous-hiccup> :ptr <root-view-ptr>}}` in a module-level atom.
  - First call: render the tree from scratch, attach to root view of the app.
  - Subsequent calls: **tear down the previous tree entirely**, render the new one from scratch. This is the "naive" reconciler — no diffing, just full replacement.
  - All operations dispatched to main thread via `repl/on-main`.

- **4.2.3** Implement teardown:
  - Walk the old tree's view pointers, call `removeFromSuperview` on each.
  - Release all from the retention registry.
  - Clear event dispatch table entries for removed views.

- **4.2.4** Implement `^{:key ...}` metadata handling:
  - Elements with `:key` metadata are tagged in the view registry by key.
  - For now (naive reconciler), keys are ignored during reconciliation — they exist so Phase 4.3's smart reconciler can use them.

- **4.2.5** Integration test:
  - Render a tree with a label, a button with `:on-tap`, and a stack with 3 children.
  - Screenshot, verify layout.
  - Re-render with different text, screenshot, verify update.
  - Tap the button (via simulator input or direct event call), verify callback fires.

**LOC estimate:** ~150.

### Phase 4.3: Smart Reconciler and Reactive Bindings

**Depends on:** Phase 2 (KVO) for reactive bindings. Can build smart reconciler independently.

**Tasks:**

- **4.3.1** Implement tree diffing:
  - Walk old tree and new tree in parallel.
  - Same tag + same key (or same position if no key): diff props, patch only changed ones, recurse into children.
  - Different tag: tear down old, build new.
  - Extra children in new: create and append.
  - Missing children in new: tear down and remove.
  - Reordered children (same keys, different positions): reorder via `removeFromSuperview` + `insertSubview:atIndex:`.
  - Output is a list of operations: `[:set-prop ptr :text "new"]`, `[:add-child parent child index]`, `[:remove-child parent child]`, `[:reorder parent child index]`.

- **4.3.2** Implement operation executor:
  - Takes the operation list, batches into a single `repl/on-main` block, executes all ops.

- **4.3.3** Implement `(defview name args body)` macro:
  - Defines a function that returns hiccup.
  - Used as: `(defview todo-item [item] [:label {:text (:title item)}])`.
  - Not reactive on its own — it's just a named component function.

- **4.3.4** Implement `(mount! root-key render-fn)`:
  - Calls `render-fn` (which should return hiccup).
  - During the call, rebinds `clojure.core/deref` to a tracking version that records every atom/ref/agent dereferenced.
  - Installs `add-watch` on each tracked reference.
  - On any change: schedules a re-render (debounced — coalesce rapid changes into one render per animation frame).
  - Uses the smart reconciler to diff and patch.

  **SCI compatibility warning:** rebinding `deref` inside SCI requires special handling. SCI resolves `deref` to `clojure.core/deref` at read time — you can't rebind it with `binding`. Instead, wrap the tracking in the atoms themselves: use a `TrackingAtom` wrapper that delegates to the real atom but records accesses in a thread-local. This is how Reagent actually works — it wraps `ratom`, not `deref`.

  **Alternative for v1:** skip auto-tracking entirely. Require the user to explicitly declare dependencies:
  ```clojure
  (mount! ::root [todos-atom filter-atom]
    (fn [] (todo-list @todos-atom @filter-atom)))
  ```
  The mount installs watches on the declared atoms. Less magic, more explicit, SCI-safe. **Recommend this for v1.**

- **4.3.5** Implement debounced re-render:
  - On watch fire, set a flag in an atom.
  - A scheduled main-thread callback (via `dispatch_after` or `performSelector:withObject:afterDelay:`) checks the flag, clears it, and re-renders.
  - Coalesces rapid changes (e.g., multiple atom swaps in one transaction) into one render.

- **4.3.6** Tests:
  - JVM: tree diff produces correct operation list for prop changes, child additions, removals, reorders.
  - JVM: debounce coalesces multiple triggers into one render.
  - Integration: mount a view with an atom, swap the atom, verify the view updates on screen.

**LOC estimate:** ~350 for reconciler + reactive bindings. ~200 for tests.

---

## Phase 5: Animations

**Depends on:** C shim batch (`grease_animate`, `grease_animate_spring`). Phase 4 (hiccup) for view creation, but animations can also be used standalone.

### File: `src/grease/ios/anim.clj`

### Tasks:

- **5.1** Add C shims to Bridge.m (or `GreaseShims.m`):
  ```objc
  void grease_animate(double duration, int curve, void *animations, void *completion) {
      [UIView animateWithDuration:duration
                           delay:0
                         options:(UIViewAnimationOptions)curve
                      animations:^{ ((GreaseVoidFn)animations)(); }
                      completion:^(BOOL finished) {
                          if (completion) ((GreaseVoidFn)completion)();
                      }];
  }

  void grease_animate_spring(double duration, double delay, double damping,
                             double velocity, int options,
                             void *animations, void *completion) {
      [UIView animateWithDuration:duration delay:delay
             usingSpringWithDamping:damping initialSpringVelocity:velocity
                           options:(UIViewAnimationOptions)options
                        animations:^{ ((GreaseVoidFn)animations)(); }
                        completion:^(BOOL finished) {
                            if (completion) ((GreaseVoidFn)completion)();
                        }];
  }
  ```

- **5.2** Implement `(animate opts body-fn)`:
  - `opts`: `{:duration 0.3 :curve :ease-in-out}` (curve maps to UIViewAnimationOptions integer).
  - `body-fn`: a zero-arg function that sets view properties (frame, alpha, center, color, transform).
  - Wraps `body-fn` in a void block, passes to `grease_animate`.
  - Returns a promise that resolves when the completion block fires.

- **5.3** Implement `(spring opts body-fn)`:
  - `opts`: `{:duration 0.5 :damping 0.7 :velocity 1.0}`.
  - Passes to `grease_animate_spring`.
  - Returns promise.

- **5.4** Implement `(sequence & animation-forms)`:
  - Each form is `[opts body-fn]`.
  - Chains: completion of animation N starts animation N+1.
  - Returns a promise that resolves when the final animation completes.

- **5.5** Implement the `anim->` threading macro:
  - Records property changes, returns them as a fn. Used inside `animate`:
  ```clojure
  (animate {:duration 0.3}
    (anim-> view
      (alpha 0.5)
      (frame [0 100 300 44])
      (bg :red)))
  ```
  Expands to a function that calls `set-alpha!`, `set-frame!`, `set-background-color!` on `view`.

- **5.6** Implement animation curve keyword → integer mapping:
  ```clojure
  {:ease-in-out  (bit-shift-left 0 16)  ; UIViewAnimationOptionCurveEaseInOut
   :ease-in      (bit-shift-left 1 16)
   :ease-out     (bit-shift-left 2 16)
   :linear       (bit-shift-left 3 16)}
  ```

- **5.7** Tests:
  - JVM: verify `sequence` chains correctly (mock animation completes immediately).
  - JVM: verify `anim->` produces the right property-set calls.
  - Integration: animate a label's alpha from 0 to 1, screenshot mid-animation and after, verify visual change. Tag `^:integration`.

**LOC estimate:** ~150 for Clojure. ~40 for C shims.

---

## Phase 6: Layout as Data

**Depends on:** Phase 4 (hiccup) for the view references. C shims for `safeAreaInsets`.
**Note:** Auto Layout via `NSLayoutConstraint` does NOT need struct shims — all constraint creation and property access uses standard `msg-send` with scalar arguments.

### File: `src/grease/ios/layout.clj`

### Tasks:

- **6.1** Implement `(constrain! parent constraint-forms)`:
  - Each constraint form: `(= (:top header) (:bottom nav-bar) 8)` meaning "header.top = nav-bar.bottom + 8".
  - The form is a Clojure list (not a macro — it's data that gets interpreted).
  - Attribute keywords: `:top`, `:bottom`, `:leading`, `:trailing`, `:width`, `:height`, `:center-x`, `:center-y`.
  - Special targets: `:safe` (safe area layout guide), `:parent` (the parent view).

- **6.2** Implement attribute keyword → `NSLayoutAttribute` integer mapping:
  ```clojure
  {:left 1 :right 2 :top 3 :bottom 4 :leading 5 :trailing 6
   :width 7 :height 8 :center-x 9 :center-y 10
   :last-baseline 11 :first-baseline 12}
  ```

- **6.3** Implement constraint compilation:
  - For each constraint form, call:
    ```clojure
    (objc-rt/msg-send :pointer
      (grease/get-objc-class "NSLayoutConstraint")
      "constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:"
      :pointer view1 :int64 attr1 :int64 relation
      :pointer view2 :int64 attr2 :float64 multiplier :float64 constant)
    ```
  - Then activate: `(objc-rt/msg-send :void constraint "setActive:" :int8 1)`.
  - Also set `translatesAutoresizingMaskIntoConstraints = NO` on every constrained view.

- **6.4** Implement the `(:attr view)` reference resolver:
  - Takes a keyword attribute and a view name.
  - Resolves the view name from the parent's tag registry (established by hiccup `:tag` or `:id` props).
  - Returns `[view-ptr attribute-int]`.

- **6.5** Integrate with hiccup:
  - A `:constraints` prop on a container view: `[:view {:constraints [...]} ...]`.
  - After rendering children, the reconciler calls `constrain!` with the constraint list.
  - On re-render, deactivate old constraints, activate new ones.

- **6.6** Add C shims for safe area insets:
  ```objc
  double grease_safe_area_top(void *view) {
      return ((__bridge UIView *)view).safeAreaInsets.top;
  }
  // ... bottom, left, right
  ```

- **6.7** Tests:
  - JVM: constraint compilation produces correct `msg-send` call sequences.
  - Integration: constrain a label to safe area top + leading, screenshot, verify position. Tag `^:integration`.

**LOC estimate:** ~200.

---

## Phase 7: Completion Handlers as Promises

**Depends on:** Phase 0 (specifically blocks.clj already exists).

### File: `src/grease/ios/completion.clj`

### Tasks:

- **7.1** Implement `(with-completion block-type callback-shape)`:
  - Returns `[block-ptr promise]`.
  - `block-type`: `:void`, `:data`, `:bool-error` (matching existing block factories).
  - The block, when invoked by iOS, delivers the coerced result to the promise.
  - The block pointer is retained automatically.

- **7.2** Implement `(let-completion bindings & body)`:
  - Syntactic sugar for sequential async operations.
  - Each binding: `[result (some-async-call-returning-promise)]`.
  - Deref's the promise with a timeout (configurable, default 30s).
  - Throws on timeout or error.
  - Purely macro-based — expands to nested `let` with `deref`.

- **7.3** Implement common completion wrappers for high-traffic APIs:
  - `(request-authorization! media-type)` → promise of boolean (wraps AVCaptureDevice + bool-error block).
  - `(fetch-url! url)` → promise of `{:data :response :error}` (wraps NSURLSession + data block).
  - These are thin wrappers that demonstrate the pattern. Not exhaustive.

- **7.4** Tests:
  - JVM: mock block invocation delivers to promise.
  - JVM: `let-completion` chains correctly.
  - Integration: `fetch-url!` returns data from a known URL. Tag `^:integration`.

**LOC estimate:** ~100.

---

## Phase 8: `defscreen` — The Synthesizer

**Depends on:** Phases 1 (delegates), 2 (KVO), 4 (hiccup). Optional: Phases 5 (animations), 6 (layout).

### File: `src/grease/ios/screen.clj`

### Tasks:

- **8.1** Define the `defscreen` macro input shape:
  ```clojure
  (defscreen <name>
    :state {<initial-state-map>}

    :delegates
    {<key> (defdelegate :inline <protocol> <method-specs>)}

    :observe
    {<key> [<object-expr> <key-path>]}

    :on-mount
    (fn [{:keys [state delegates]}]
      ;; Setup code. Return a resource map for teardown.
      ...)

    :on-unmount
    (fn [{:keys [resources]}]
      ;; Teardown code.
      ...)

    :view
    (fn [{:keys [state]}]
      ;; Return hiccup.
      ...))
  ```

- **8.2** Implement lifecycle management:
  - `(present! screen-name)`:
    - Create an agent from `:state`.
    - Create delegates from `:delegates` specs.
    - Set up KVO observations from `:observe`.
    - Call `:on-mount` with `{:state agent :delegates delegate-map}`.
    - Store returned resources.
    - Mount the `:view` function with reactive bindings (Phase 4.3's `mount!`).
    - All ObjC work dispatched to main thread.
  - `(dismiss! screen-name)`:
    - Call `:on-unmount` with resources.
    - Release all KVO observations.
    - Release all delegates.
    - Tear down the view tree.
    - Release all retained objects for this screen.

- **8.3** Implement screen-scoped retention:
  - When a screen is presented, all `retain!` calls from its setup are tagged with the screen's key.
  - `dismiss!` calls `release-kind!` with the screen key — bulk cleanup.

- **8.4** Implement the `:inline` delegate shorthand:
  - `(defdelegate :inline ...)` doesn't `def` anything — it returns the factory function directly for use inside the screen's `:delegates` map.

- **8.5** Tests:
  - JVM: present/dismiss lifecycle fires in correct order.
  - JVM: dismiss releases all retained objects.
  - Integration: present a screen with a label and a location delegate, verify it renders and receives callbacks. Dismiss and verify cleanup. Tag `^:integration`.

**LOC estimate:** ~250.

---

## Dependency Graph

```
Phase 0.1 (retain)
  │
  ├──► Phase 1 (delegates/agents)
  │      │
  ├──► Phase 2 (KVO/add-watch)
  │      │
  ├──► Phase 3 (notifications)
  │
  ├──► Phase 0.4 (with-autorelease)
  ├──► Phase 0.5 (with-retained)
  │
Phase 0.2 (color) ──┐
Phase 0.3 (font) ───┤
                    ▼
              Phase 4.1 (hiccup elements)
                    │
              Phase 4.2 (naive reconciler)
                    │
              Phase 4.3 (smart reconciler + reactive)  ◄── Phase 2
                    │
              Phase 5 (animations)  [+ C shim batch]
                    │
              Phase 6 (layout)
                    │
Phase 7 (completion) ── standalone, depends only on blocks.clj
                    │
              Phase 8 (defscreen) ◄── Phases 1, 2, 4
```

The critical path is: **0.1 → 0.2 + 0.3 → 4.1 → 4.2 → 4.3** (with Phase 2 feeding into 4.3). Everything else can be parallelized around this spine.

---

## Timeline Estimates (Claude Opus + Simulator MCP Loop)

| Phase | Work | Calendar |
|---|---|---|
| 0.1–0.5 | Foundations | 1 day |
| 1 | Delegates as agents | 1 day |
| 2 | KVO as add-watch | 1 day |
| C shim batch | All Bridge.m additions | ½ day (slow loop — one rebuild) |
| 3 | Notifications | ½ day |
| 4.1 | Hiccup element creation | 1 day |
| 4.2 | Naive reconciler | ½ day |
| 4.3 | Smart reconciler + reactive | 1.5 days |
| 5 | Animations | 1 day |
| 6 | Layout | 1 day |
| 7 | Completion handlers | ½ day |
| 8 | defscreen | 1 day |
| | **Total** | **~10 days active work** |

Budget 3–4 extra days for SCI/native-image debugging, unforeseen struct issues, and your review cycles. **Realistic calendar: 2–3 weeks.**

---

## Appendix A: SCI Compatibility Checklist (Apply to Every Phase)

Before merging any new file into the SCI-loaded set:

- [ ] No nested backticks. Compute inner forms with `mapv` outside the outer backtick.
- [ ] All symbols inside syntax-quote are fully qualified.
- [ ] No `core.async` (use `promise` and `add-watch`).
- [ ] No `clojure.spec.alpha` runtime calls.
- [ ] No `requiring-resolve` in hot paths (cache in atoms).
- [ ] `*warn-on-reflection*` clean.
- [ ] Loads via `(sci/eval-string* ctx (slurp ...))` without error.
- [ ] Custom types (`IDeref`, `IRef`) work under SCI. **Test this early** — SCI may not support `deftype` with protocol implementations. If not, fall back to maps + functions with the same API shape.
- [ ] Thread-locals and `binding` work correctly under SCI.

### Appendix B: Files Touched in Bridge.m / Xcode (Slow-Loop Changes)

Batch all of these into one commit before Phase 4:

```objc
// GreaseShims.m — new file, add to Xcode compile sources

// Bounds (like frame but for bounds rect)
void grease_set_bounds(void *view, double x, double y, double w, double h);
double grease_get_bounds_x(void *view);
double grease_get_bounds_y(void *view);
double grease_get_bounds_w(void *view);
double grease_get_bounds_h(void *view);

// Safe area insets
double grease_safe_area_top(void *view);
double grease_safe_area_bottom(void *view);
double grease_safe_area_left(void *view);
double grease_safe_area_right(void *view);

// Transforms (CGAffineTransform — 6 components)
void grease_set_transform(void *view, double a, double b, double c, double d, double tx, double ty);
double grease_get_transform_a(void *view);
double grease_get_transform_b(void *view);
double grease_get_transform_c(void *view);
double grease_get_transform_d(void *view);
double grease_get_transform_tx(void *view);
double grease_get_transform_ty(void *view);

// Animation wrappers
void grease_animate(double duration, int curve, void *animations, void *completion);
void grease_animate_spring(double duration, double delay, double damping,
                           double velocity, int options,
                           void *animations, void *completion);

// Content offset/size for scroll views (CGPoint / CGSize structs)
double grease_get_content_offset_x(void *view);
double grease_get_content_offset_y(void *view);
void grease_set_content_offset(void *view, double x, double y, int animated);
double grease_get_content_size_w(void *view);
double grease_get_content_size_h(void *view);
void grease_set_content_size(void *view, double w, double h);

// Content insets for scroll views (UIEdgeInsets — 4 components)
double grease_get_content_inset_top(void *view);
double grease_get_content_inset_left(void *view);
double grease_get_content_inset_bottom(void *view);
double grease_get_content_inset_right(void *view);
void grease_set_content_inset(void *view, double top, double left, double bottom, double right);
```

That's ~35 functions, ~150 lines of trivial Objective-C. One file, one rebuild, unlocks everything.