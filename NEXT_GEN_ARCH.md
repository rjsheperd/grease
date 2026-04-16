# Grease Data-Driven iOS Wrappers — Architecture & Build Script

**Audience:** Claude Code, working inside the existing `grease` repo.
**Prerequisite reading:** the project's `CLAUDE.md` and `obj-c-dynamic.md`. Read them. Twice.
**Style:** data-driven, runtime-dispatched, zero per-framework generated source.

---

## 0. Read This First

This work lives inside an existing, working project. The bridge layer is built. The hand-written wrappers (`grease.ios.foundation`, `grease.ios.bluetooth`, `grease.ios.uikit`, `grease.ios.camera`, `grease.ios.blocks`) are working reference implementations — **do not delete or rewrite them yet**. They are the oracle the engine is validated against.

The goal is to introduce a small generic engine that lets new frameworks be added by dropping an EDN file into `resources/grease/api-specs/`, with no per-framework `.clj` file written. Once the engine demonstrably reproduces the behavior of the hand-written wrappers, those wrappers can be deprecated and replaced with EDN — but that is a *later* step, not part of the initial cut.

If you find yourself writing `src/grease/ios/avfoundation.clj` (a new file modeled on `bluetooth.clj`), stop. You are off the rails.

---

## 1. What Already Exists (Use This, Don't Replace It)

### 1.1 The bridge — already done

The "bridge layer" in the prior architecture document is a fiction in this codebase. The real bridge is `com.phronemophobic.grease` plus `grease.ios.objc`, both already loaded into the SCI context at native-image build time. The engine **calls these directly**. There is no `ios.bridge` namespace to write.

The functions the engine will lean on:

```clojure
;; Class lookup and instance creation
(grease/get-objc-class "AVPlayer")           ; -> Class pointer
(grease/objc-new cls)                         ; -> instance pointer (sends +new)

;; Selectors
(grease/register-objc-sel "play")             ; -> SEL pointer (interned)

;; Method dispatch — the workhorse
(objc-rt/msg-send :void   obj "play")
(objc-rt/msg-send :int64  obj "length")
(objc-rt/msg-send :pointer obj "stringByAppendingString:" :pointer other)

;; Defining ObjC subclasses (for delegates)
(objc-rt/defclass MyDelegate "NSObject"
  "fooBarBaz:" "v@:@" (fn [_self _cmd arg] ...))

;; Hot-reloadable variant — ALWAYS use this from REPL
(repl/defclass! MyDelegate ...)               ; appends _vN suffix per eval
```

### 1.2 Type coercion — already done for Foundation primitives

`grease.ios.foundation` already has bidirectional converters: `->nsstring`, `nsstring->str`, `->nsnumber-long`, `nsnumber->double`, `nsarray->vec`, `->nsarray`, `nsdict->map`, `->nsdict`, `nserror->map`. Plus `null-ptr` and `main-queue` helpers because clj-libffi rejects Java `nil` for `:pointer` args.

The engine's type coercion layer is a **dispatch table** on top of these existing functions, not a reimplementation.

### 1.3 ObjC type encoding strings

ObjC has its own type encoding language (`"v@:@@"` = void method taking two id args after self/_cmd). The codebase already uses this. The engine **adopts this encoding directly** rather than inventing a parallel type DSL. The EDN spec stores ObjC encoding strings; the engine parses them at load time.

Encoding cheat sheet (from `grease.ios.objc`):
- `v` void · `@` id/object · `:` SEL · `q` long long · `i` int · `f` float · `d` double · `c` char · `B` BOOL · `*` C string · `^...` pointer-to

### 1.4 SCI / native-image constraints that constrain *everything*

These are not theoretical — they are present in the `defclass` macro implementation as load-bearing comments. Every macro in the engine must obey them:

1. **No nested backticks.** SCI's gensym counter restarts between backtick levels in the frozen native-image, producing `cls__30__auto__` unresolved-symbol errors. Compute inner forms with `mapv` outside the outer backtick. Look at `grease.ios.objc/defclass` for the canonical pattern.

2. **Fully-qualified symbols inside backtick.** SCI resolves syntax-quoted symbols at expansion time in the *calling* namespace. Aliases will not work. Always write `com.phronemophobic.grease/get-objc-class`, never `grease/get-objc-class` inside a backtick.

3. **No colon-containing selectors via `objc` macro.** SCI's reader rejects `setDelegate:` as a symbol. The engine must always go through `objc-rt/msg-send`, never the `objc` macro.

4. **ARC retention.** Any ObjC object the engine creates that needs to outlive the call (delegates, observers) must be stored in an atom. The engine needs an internal retention registry.

5. **Main-thread requirement.** UIKit, CLLocationManager, CBCentralManager, and most delegate wiring must run on the main thread. The engine's `make`/`call` should accept a `:on-main? true` option and use `repl/on-main` or `grease/dispatch-main-async`.

These constraints mean the engine code must be **carefully structured to be SCI-compatible**, since it will run inside the same SCI context that loads the existing wrappers. Test that it loads under SCI early.

### 1.5 What NOT to assume from the prior architecture doc

- There is no `ios.bridge` namespace. The bridge is `grease` + `grease.ios.objc`.
- There is no `ios.core`. The public API lives at `grease.ios.api` (or similar — keep the `grease.ios.*` namespace prefix to match convention).
- `core.async` is not currently in the project's dependency graph, and may not be SCI-compatible inside the frozen native-image. **Use plain `clojure.core/promise` and `add-watch` instead** until proven otherwise. KVO can return a promise of the next value, or take an `on-change` callback. Skip the channel abstraction in v1.
- `clojure.spec.alpha` may or may not survive native-image freezing depending on the build flags (`-Dclojure.spec.skip-macros=true` is set in `:depstar`). Do not rely on it for runtime validation. Use plain `ex-info` checks.

---

## 2. Project Layout (Inside the Existing Repo)

```
src/grease/ios/
  objc.clj              ← EXISTING, low-level — DO NOT MODIFY
  repl.clj              ← EXISTING — defclass!, on-main, introspection
  foundation.clj        ← EXISTING reference wrapper
  bluetooth.clj         ← EXISTING reference wrapper
  uikit.clj             ← EXISTING reference wrapper
  camera.clj            ← EXISTING reference wrapper
  blocks.clj            ← EXISTING reference wrapper
  ─── new files below ───
  spec.clj              ← load + validate EDN specs
  naming.clj            ← apply naming rules
  types.clj             ← type dispatch table over foundation/* coercers
  patterns.clj          ← delegate proxies, KVO, completion handlers
  registry.clj          ← in-memory registry + ARC retention table
  invoke.clj            ← runtime dispatch: spec lookup → msg-send
  api.clj               ← public surface: make, call, get-prop, set-prop!, watch
  gen.clj               ← optional defframework! macro

resources/grease/
  api-specs/
    Foundation.edn      ← will be populated; reference against foundation.clj
    AVFoundation.edn
    ...
  naming.edn
  types.edn
  patterns.edn

src/grease/scrape/      ← optional, for v2 — Apple docs → EDN
  apple_docs.clj
  normalize.clj

tools/scrape.clj        ← CLI entry, alias :scrape

test/grease/ios/
  spec_test.clj
  naming_test.clj
  types_test.clj
  patterns_test.clj
  invoke_test.clj
  oracle_test.clj       ← runs engine against the same calls foundation/uikit
                          /bluetooth perform, asserts identical results
```

Files not in this layout should not be created. In particular: no `src/grease/ios/<framework>.clj` for any framework not already present.

### 2.1 SCI registration

When ready to expose the engine to the on-device REPL, add the new namespaces to `com.phronemophobic.grease`'s SCI options and pre-load them at SCI context startup, the same way `grease.ios.objc` and `grease.ios.foundation` are pre-loaded today (see lines ~242-245 of `grease.clj`). This is the *last* step, after the engine works in plain JVM tests.

---

## 3. Data Formats

### 3.1 API spec EDN (`resources/grease/api-specs/<Framework>.edn`)

Use ObjC type encoding strings directly. No parallel type DSL.

```clojure
{:framework  "AVFoundation"
 :doc-url    "https://developer.apple.com/documentation/avfoundation"
 :spec-hash  "<sha256 of file content with this key blanked>"
 :classes
 [{:name       "AVPlayer"
   :init       [{:selector "init"          :encoding "@@:"     :args []}
                {:selector "initWithURL:"  :encoding "@@:@"    :args [{:name "url" :type "NSURL" :nullable false}]}]
   :methods    [{:selector "play"          :encoding "v@:"     :args [] :return "void"}
                {:selector "pause"         :encoding "v@:"     :args [] :return "void"}
                {:selector "seekToTime:"   :encoding "v@:{CMTime=qiIq}"
                 :args [{:name "time" :type "CMTime"}]
                 :return "void"
                 :main-thread? true}]
   :properties [{:name "status" :type "AVPlayerStatus" :encoding "q" :readonly true :kvo true}
                {:name "volume" :type "Float"          :encoding "f" :readonly false}]}]
 :enums
 [{:name "AVPlayerStatus" :encoding "q"
   :values [{:name "unknown" :raw 0}
            {:name "readyToPlay" :raw 1}
            {:name "failed" :raw 2}]}]
 :constants []}
```

**Rules:**
- Filename matches `:framework` exactly.
- Every `:type` string must resolve in `resources/grease/types.edn`, OR be a class defined in any loaded spec, OR be an enum defined in any loaded spec.
- `:encoding` is the source of truth for what `msg-send` will use. If `:encoding` and `:type` disagree, the loader rejects the spec.
- Struct types (`CMTime`, `CGRect`, etc.) are out of scope for v1. Methods that take or return structs get tagged `:unsupported true` by the scraper and are skipped at load time with a warning.

### 3.2 Naming rules (`resources/grease/naming.edn`)

```clojure
{:rules
 [{:scope :method   :match #"^is(.+)$"  :transform :strip+question
   :examples {"isPlaying" "playing?" "isHidden" "hidden?"}}
  {:scope :method   :match #"^has(.+)$" :transform :strip+question
   :examples {"hasPrefix" "prefix?"}}
  {:scope :setter   :transform :wrap-set-bang
   :examples {"volume" "set-volume!"}}
  {:scope :method   :transform :camel->kebab
   :examples {"startRunning" "start-running"
              "stringByAppendingString:" "string-by-appending-string"}}
  {:scope :enum-val :transform :camel->kebab-keyword
   :examples {"readyToPlay" :ready-to-play}}
  {:scope :class    :transform :strip-framework-prefix
   :examples {"AVPlayer" "player" "NSURL" "url" "CBCentralManager" "central-manager"}}]}
```

Selectors with colons are flattened first (`"setObject:forKey:"` → `setObject-forKey-` → `set-object-for-key`). Keep the original selector as the lookup key in the registry; the kebab name is the user-facing API.

`:examples` is mandatory and doubles as test data (Section 7).

### 3.3 Type bridge (`resources/grease/types.edn`)

Maps each type to the existing `grease.ios.foundation/*` functions, by symbol so the data is portable.

```clojure
{"NSString"   {:encoding "@" :clj :string :coerce-in  grease.ios.foundation/->nsstring
                                          :coerce-out grease.ios.foundation/nsstring->str}
 "NSNumber"   {:encoding "@" :clj :number :coerce-in  grease.ios.foundation/->nsnumber-long
                                          :coerce-out grease.ios.foundation/nsnumber->long}
 "NSArray"    {:encoding "@" :clj :seq    :coerce-in  grease.ios.foundation/->nsarray
                                          :coerce-out grease.ios.foundation/nsarray->vec}
 "NSDictionary" {:encoding "@" :clj :map  :coerce-in  grease.ios.foundation/->nsdict
                                          :coerce-out grease.ios.foundation/nsdict->map}
 "NSURL"      {:encoding "@" :clj :string :coerce-in  grease.ios.foundation/string->nsurl
                                          :coerce-out grease.ios.foundation/nsurl->string}
 "BOOL"       {:encoding "B" :clj :boolean :coerce-in #(if % 1 0) :coerce-out #(not= 0 %)}
 "NSInteger"  {:encoding "q" :clj :long    :coerce-in long       :coerce-out identity}
 "Float"      {:encoding "f" :clj :double  :coerce-in double     :coerce-out identity}
 "Double"     {:encoding "d" :clj :double  :coerce-in double     :coerce-out identity}
 "void"       {:encoding "v" :clj :nil     :coerce-in (constantly nil) :coerce-out (constantly nil)}
 "id"         {:encoding "@" :clj :pointer :coerce-in identity   :coerce-out identity}}
```

**Loader behavior:** symbols in `:coerce-in`/`:coerce-out` are resolved via `requiring-resolve` at load time, then cached as functions in the runtime type table. This keeps the EDN human-readable and the runtime fast.

**Class types** (`AVPlayer`, `CBPeripheral`) are auto-registered with `{:encoding "@" :clj :pointer :coerce-in identity :coerce-out identity}` when their framework spec loads. Specifying nothing for a class means "pass and return raw pointers."

### 3.4 Pattern config (`resources/grease/patterns.edn`)

```clojure
{:patterns
 {:completion-handler {:wrap :promise}
  :delegate           {:wrap :proxy   :requires-protocol true}
  :kvo                {:wrap :callback :on-dealloc :detach}
  :block              {:wrap :fn}
  :main-thread        {:wrap :dispatch-main}}}
```

Each pattern key is referenced from a method spec via `{:type :completion-handler}` or `{:main-thread? true}`, and dispatched via multimethod in `grease.ios.patterns`.

---

## 4. The Engine

Every namespace below is a few hundred LOC at most. The whole engine should land under 1000 LOC total.

### 4.1 `grease.ios.spec` — Loader

**Responsibilities:**
- Read every `.edn` file from `resources/grease/api-specs/` (use `clojure.java.io/resource` so it works from a jar).
- Validate structurally: required keys present, every `:type` resolvable, every `:encoding` a parseable ObjC type string.
- Reject specs with unresolvable types loudly (with `ex-info` listing offenders), so scraper bugs surface immediately.
- Return a registry map keyed by `:framework` lower-kebab keyword.

**Public API:**
- `(load-all)` → registry map
- `(load-one path)` → single framework map, validated
- `(validate spec)` → throws on failure with explanatory `ex-data`

The validator is generic; it does not know the names of any specific frameworks.

### 4.2 `grease.ios.naming` — Apply rules

**Responsibilities:**
- Load `naming.edn` once at startup.
- Apply the rule list to transform Obj-C names → Clojure names, and vice versa, for two-way lookup.
- Pre-compute the transformed names for every spec at load time, so runtime dispatch is a hash lookup.

**Public API:**
- `(->clj scope obj-c-name)` → string/symbol/keyword
- `(->objc scope clj-name)` → string
- `(transform-spec spec)` → spec annotated with `::clj-name` on every class/method/property/enum, plus reverse-index maps

**Implementation:** each `:transform` keyword dispatches through a multimethod. New transforms = new `defmethod`, no other code changes.

### 4.3 `grease.ios.types` — Coercion

**Responsibilities:**
- Load `types.edn` once, resolve coercer symbols to functions.
- Provide fast lookup of coercers by type name.
- Auto-register class types as opaque pointers when their framework spec loads.

**Public API:**
- `(coerce-in type-name value)` → bridge-ready value, may return `(f/null-ptr)` for `nil` pointers
- `(coerce-out type-name value)` → Clojure value
- `(register-class! class-name)` — called by `spec/load-one` for every class in a spec
- `(encoding-for type-name)` → ObjC encoding char, used by `invoke` to build the `msg-send` arg list

**`nil` handling:** for any `:pointer` type, `nil` in the input becomes `(f/null-ptr)` automatically. This eliminates the most common failure mode (clj-libffi rejecting Java `nil`).

### 4.4 `grease.ios.patterns` — Cross-boundary idioms

**Responsibilities:**
- Convert Clojure idioms to ObjC idioms and back for the patterns listed in §3.4.

**Initial v1 patterns:**

- **`:completion-handler`** — method spec marks an arg as `{:type :completion-handler :signature ["NSError"]}`. The user passes nothing for that arg; the engine creates a block (using `grease.ios.blocks`) that delivers to a returned promise. The user dereferences the promise with a timeout.
- **`:delegate`** — user passes a map of `{:keyword fn}`. The engine looks up the protocol in the spec, builds an ObjC class with `objc-rt/defclass!` whose methods dispatch into the map, instantiates it, and **stores it in the retention registry keyed by a hash of the map**. Returning the same map twice returns the same delegate (idempotent — important for KVO and re-evaluation).
- **`:kvo`** — wraps `addObserver:forKeyPath:options:context:`. The engine creates an internal observer class once at startup (during `patterns/init!`), holds it forever, and routes notifications to a callback registered by `(api/watch obj :prop on-change-fn)`. Returns an opaque handle; `(api/unwatch handle)` deregisters.
- **`:block`** — the engine uses `grease.ios.blocks` to wrap a Clojure fn into an NSBlock pointer.
- **`:main-thread`** — when a method's spec has `:main-thread? true`, the engine wraps the entire `msg-send` call in `repl/on-main`.

**Public API:**
- `(wrap-arg pattern-key method-spec value)` → `[bridge-value retain-handle]`
- `(wrap-return pattern-key method-spec value)` → Clojure value (e.g., promise)
- `(init!)` — called once at engine startup; creates the internal KVO observer class

### 4.5 `grease.ios.registry` — In-memory state

Three atoms:
- **Spec registry** — `{:av-foundation {...} :foundation {...}}`, populated by `spec/load-all`.
- **Class index** — `{"AVPlayer" :av-foundation, "NSString" :foundation, ...}` for fast class-name → framework lookup.
- **Retention table** — `{<hash> ptr}` for delegates, observers, and any other ObjC object the engine creates that must outlive its call. This is the engine's ARC store.

Convenience lookups: `class-spec`, `method-spec`, `enum-keyword-for`, `enum-raw-for`, `retain!`, `release!`.

Reset on `(api/reload!)`.

### 4.6 `grease.ios.invoke` — Runtime dispatch

**This is the heart.** All the public-facing `api` functions resolve through here.

Algorithm for `(invoke obj selector-or-clj-name args opts)`:

1. Resolve `obj`'s class via `repl/class-name-of`.
2. Look up the method spec in the registry. If `selector-or-clj-name` is a keyword, use the precomputed `::clj-name` index. If a string, treat as raw selector.
3. Parse the method's `:encoding` to get the return-type and per-arg encoding chars.
4. Walk the args:
   - If the arg spec has `:type` matching a pattern key, route through `patterns/wrap-arg`.
   - Otherwise, run `types/coerce-in` for the declared `:type`.
   - Build the flat alternating `[type-keyword value type-keyword value ...]` list that `objc-rt/msg-send` expects.
5. If `:main-thread?` is set on the method, wrap the call in `repl/on-main`.
6. Call `(apply objc-rt/msg-send ret-type obj selector typed-args)`.
7. Apply `types/coerce-out` (or `patterns/wrap-return`) to the result.
8. If the method returned an enum's raw type, apply `registry/enum-keyword-for` first.
9. Return.

Class methods use `(grease/get-objc-class)` then `msg-send` against the class pointer. Property reads/writes are just sugar for the synthesized getter/setter selectors.

### 4.7 `grease.ios.api` — Public surface

The user-facing API. Thin facade.

```clojure
(require '[grease.ios.api :as ios])

;; Construction
(ios/make :av-player {:url "https://example.com/song.m4a"})
(ios/make :string "hello")                   ; -> NSString*

;; Method dispatch
(ios/call player :play)                      ; -> nil (void)
(ios/call player :seek-to-time {:seconds 30})

;; Properties
(ios/get-prop  player :status)               ; -> :ready-to-play (enum auto-decoded)
(ios/set-prop! player :volume 0.5)

;; KVO (returns a handle; pass it to unwatch)
(def h (ios/watch player :status (fn [old new] (println old "->" new))))
(ios/unwatch h)

;; Enum helpers (no class needed)
(ios/enum :av-player-status :ready-to-play)  ; -> 1
(ios/enum :av-player-status 1)                ; -> :ready-to-play

;; Engine lifecycle
(ios/load!)           ; load all specs (called once at REPL start)
(ios/reload!)         ; reload from disk — picks up edited EDN
```

No per-framework code. Framework prefix is inferred from the class index.

### 4.8 `grease.ios.gen` — Optional sugar

For users who want `(av-foundation/play player)` instead of `(ios/call player :play)`.

```clojure
(ios.gen/defframework! :av-foundation)
;; Interns vars in the av-foundation namespace at REPL eval time.
```

**Critical:** this is a runtime macro, not a build-time codegen. It does not write `.clj` files. It calls `intern` to create vars whose bodies are thin shims into `ios.invoke`. The vars exist only in the running JVM/native-image. If the EDN changes, you call `(ios.gen/redefframework! :av-foundation)` to rebuild the vars.

**SCI compatibility:** this macro will likely live outside the SCI context (used only by Clojure-on-JVM developers, not from on-device REPL). If it must be SCI-compatible, follow the constraints in §1.4 — no nested backticks, fully-qualified symbols throughout. Defer this work to v2 unless explicitly requested.

---

## 5. Build Order (Strict, Test-Gated)

Each step has a verification gate. Do not skip ahead.

### Step 0 — Read existing code

Read `grease.ios.objc`, `grease.ios.foundation`, `grease.ios.bluetooth`, `grease.ios.uikit`, `grease.ios.repl`, and `com.phronemophobic.grease`. The patterns there are the patterns the engine must reproduce. **Time investment here pays back tenfold later.**

### Step 1 — Mock bridge for testing

The engine must be testable in plain JVM without iOS hardware. Create `test/grease/ios/mock_bridge.clj` that records every `msg-send` call into an atom and returns canned responses based on a method-name → response map. Tests use `with-redefs` on `objc-rt/msg-send`, `grease/get-objc-class`, and `grease/objc-new`.

**Gate:** a test calls a fake `(api/call fake-obj :play)`, the mock records `["AVPlayer" "play" :void]`, and the test passes.

### Step 2 — Type bridge

Build `resources/grease/types.edn` with the entries in §3.3. Implement `grease.ios.types`. Write `test/grease/ios/types_test.clj` that asserts every entry's coercer functions resolve, and round-trips a sample value through each (using the mock bridge for object types).

**Gate:** all type tests pass; `(types/coerce-in "NSString" "hi")` returns the same value as `(f/->nsstring "hi")` would.

### Step 3 — Naming

Build `resources/grease/naming.edn` with the rules in §3.2. Implement `grease.ios.naming`. **Auto-derive tests from the `:examples` map of every rule** — iterating `:examples` yields `is` assertions for free.

**Gate:** every `:examples` pair round-trips successfully via `->clj` and `->objc`.

### Step 4 — Spec loader

Hand-write a tiny `resources/grease/api-specs/Foundation.edn` covering exactly the classes/methods that `grease.ios.foundation` already wraps (NSString, NSNumber, NSArray, NSDictionary, NSError). This is the minimum spec needed to validate the engine against the existing oracle. Implement `grease.ios.spec` and `grease.ios.registry`.

**Gate:** `(spec/load-all)` returns a registry containing the hand-crafted Foundation spec; validation passes; `(registry/class-spec "NSString")` returns the expected map.

### Step 5 — Invoke

Implement `grease.ios.invoke` and `grease.ios.api`. With the mock bridge from Step 1, verify the entire dispatch pipeline.

**Gate (the oracle test):** for every public function in `grease.ios.foundation`, write a test that calls the equivalent through `ios/call` or `ios/make` and asserts the recorded `msg-send` calls are byte-identical to what `foundation.clj` emits. This is the most important test in the project — if it passes, the engine is real.

### Step 6 — Patterns: delegates first

Implement `grease.ios.patterns/wrap-arg` for `:delegate` only. The mock bridge needs to handle `objc-rt/defclass!` calls (record them, return a fake class pointer). Add a hand-written CLLocationManager spec snippet to a `CoreLocation.edn` and verify that `(ios/make :location-manager)` followed by `(ios/call mgr :set-delegate {:on-update-locations (fn [...] ...)})` produces the same sequence of `defclass!` + `msg-send` calls as the existing test in `dev/test_location.clj`.

**Gate:** the recorded call sequence matches the hand-written test exactly.

### Step 7 — Patterns: KVO and completion handlers

Add `:kvo` and `:completion-handler`. KVO needs the internal observer class created at engine startup. Completion handlers integrate with `grease.ios.blocks`. Write tests with the mock bridge; integration tests are deferred until Step 9.

**Gate:** mock-bridge tests pass; `(ios/watch ...)` returns a handle and registers the callback; `(ios/unwatch handle)` deregisters.

### Step 8 — Real Foundation through the engine

Run the engine against the actual Foundation spec on a real device or simulator (via the existing build pipeline). Compare results against direct `foundation.clj` calls.

**Gate:** every Foundation operation in the existing test suite passes when routed through the engine.

### Step 9 — Real CoreLocation through the engine

Hand-write a complete `CoreLocation.edn`. Run `dev/test_location.clj` reimplemented to use only the engine. Verify GPS updates arrive, permission flow works, ARC retention holds the delegate.

**Gate:** the location test passes end-to-end with no per-framework Clojure file.

### Step 10 — Sugar (optional)

Implement `grease.ios.gen/defframework!`. Verify `(defframework! :foundation)` produces a `foundation` namespace whose vars work identically to `(ios/call ...)`.

**Gate:** parity test — every Foundation function called both ways yields identical results.

### Step 11 — Scraper (separate effort)

Build `grease.scrape.apple-docs` and `grease.scrape.normalize`. This is large enough to warrant its own design pass. Recommended: prefer Apple's documentation JSON API (`https://developer.apple.com/tutorials/data/documentation/<framework>.json`) over HTML scraping. Cache responses to `.cache/apple-docs/`. Output validated EDN to `resources/grease/api-specs/`.

**Gate:** scraping Foundation produces an EDN that, when loaded, yields a registry equivalent to the hand-crafted one from Step 4 (modulo additional symbols).

### Step 12 — Scrape the rest, fix the long tail

Run scraper across the framework table from the original org file. Each validation failure is either a scraper bug (fix the scraper) or a new pattern the engine doesn't know about (add it to `patterns.edn` and `patterns.clj`). **Never hand-edit scraped EDN as a workflow** — only as a stopgap with a `;; TODO: fix scraper` comment.

**Gate:** every framework EDN loads without validation errors. Round-trip test suite (Step 13) green.

### Step 13 — Round-trip test suite

`test/grease/ios/oracle_test.clj` iterates the loaded registry and asserts: every method has resolvable arg types, every enum round-trips both ways, every property has a coercer, every spec-declared protocol is registerable. This is the regression net for future scraper changes.

---

## 6. Testing Discipline

- **Naming and type tests** auto-derive from `:examples` and `types.edn`. Adding a rule = adding an example = getting a test for free.
- **Engine tests** (invoke, patterns) use the recording mock bridge — they run in pure JVM with zero iOS dependency.
- **Oracle tests** (Step 5, Step 9) compare engine-emitted `msg-send` sequences against hand-written wrappers. This is what proves the engine is faithful.
- **Integration tests** are tagged `^:integration` and only run on device. They mirror the existing tests in `tests/run_test_location.py`.
- **The Step 13 round-trip suite** is the single most valuable regression net.

Lint and format after every change, per `CLAUDE.md`:
```bash
clj-kondo --lint <file>
cljfmt fix <file>
```

---

## 7. SCI Compatibility Checklist

Before merging any new namespace into the SCI-loaded set:

- [ ] No nested backticks. Use `mapv` to compute inner forms.
- [ ] All symbols inside backticks are fully-qualified (`com.phronemophobic.grease/X`, never `grease/X`).
- [ ] No `clojure.spec.alpha` runtime checks (use `ex-info` instead).
- [ ] No `core.async` (use `promise` and callbacks).
- [ ] No `requiring-resolve` inside frequently-called functions (cache results in atoms).
- [ ] `*warn-on-reflection*` clean.
- [ ] Loads cleanly via `(sci/eval-string* ctx (slurp ...))`.
- [ ] Test by adding a temporary `sci/eval-string*` call at the bottom of `com.phronemophobic.grease`'s SCI context init, and rebuilding the native-image. If it loads on-device, it passes.

---

## 8. What Not To Do

- **Do not generate per-framework `.clj` files and commit them.** The hand-written ones are *legacy oracles* to validate against, not a template to imitate.
- **Do not introduce a lockfile.** EDN files *are* the artifact.
- **Do not put framework-specific logic in the engine.** A branch like `(when (= framework :av-foundation) ...)` in `invoke.clj` means a missing pattern. Add it to `patterns.edn` instead.
- **Do not hand-edit scraped EDN as a workflow.** Stopgaps only, with TODO comments.
- **Do not bypass `objc-rt/msg-send` to call `objc_msgSend` directly.** The existing function handles the SCI selector-symbol issue and is the project's contract.
- **Do not use `repl/defclass` (without the bang).** Always `defclass!` so re-evaluation doesn't crash the runtime.
- **Do not store created ObjC objects only in let-bound locals.** ARC will release them. Use the registry's retention table.
- **Do not call UIKit / CL / CB APIs off the main thread.** Tag the method `:main-thread? true` in the spec, or wrap the call site in `(repl/on-main ...)`.

---

## 9. Open Questions To Resolve With User

1. **Scope of v1**: ship engine + Foundation + CoreLocation only, then the scraper? Or attempt all ~90 frameworks in one go? **Recommendation: the former.** Engine + 2 frameworks is provable. 90 frameworks is a research project.
2. **Struct support** (CGRect, CMTime): defer to v2, or block on it? Many UIKit and AVFoundation methods take/return structs. Without struct support, those methods are unsupported. **Recommendation: defer; mark struct-using methods `:unsupported true` in the spec; revisit when the engine is otherwise complete.**
3. **`defframework!` macro**: needed in v1, or can users live with `(ios/call obj :method args)` syntax? **Recommendation: defer; the call syntax is fine and the macro adds SCI complexity.**
4. **Migration of existing wrappers**: keep `foundation.clj` etc. forever, or deprecate once the engine reproduces them? **Recommendation: keep until the engine has been in real use for at least a release cycle. The hand-written code is more readable for newcomers; the EDN is more maintainable for many frameworks.**

Bring these to the user before Step 1.

---

## 10. Success Criteria

- Engine namespaces total under 1000 LOC.
- The Step 5 oracle test passes — the engine emits identical `msg-send` sequences to `foundation.clj`.
- The Step 9 location test passes — a real ObjC delegate works through the engine on a real device.
- Adding a new framework requires zero Clojure code changes (just an EDN drop).
- No file matching `src/grease/ios/<new_framework>.clj` exists.
- Every spec-loaded framework passes the Step 13 round-trip suite.
- All new namespaces load cleanly under SCI inside the native-image.

When these hold, the architecture has earned its keep.