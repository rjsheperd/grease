# grease — Claude guidance

## What this project is

Clojure on iOS via GraalVM native-image. The build compiles Clojure to an
iOS arm64 relocatable object (`build/bb/bb.o`), links it into an Xcode app
(`xcode/MobileTest`), and exposes a babashka SCI nREPL on port 23456 for
live evaluation on the device.

## Build pipeline

Run from the repo root in order. Each script must succeed before the next.

```bash
# 1. Compile Clojure → uberjar → bb.o (takes ~90s; clears all caches first)
rm -rf build/bb build/bb-tmp target conf/capcache
./scripts/build-bb-o

# 2. Link bb.o into the Xcode app (needs DEVELOPMENT_TEAM from .envrc)
direnv exec . ./scripts/build-demo-app

# 3. Sign and install on device (kill stale ios-deploy first or it hangs at 49%)
pkill -f ios-deploy 2>/dev/null
./scripts/deploy-demo-app

# 4. Attach debugger and start the nREPL
./scripts/debug-demo-app --noinstall
```

Subsequent builds can skip `rm -rf ...` and use the CAPCache:
```bash
USE_CAP_CACHE=use ./scripts/build-bb-o
```

## nREPL connection

Device IP: `192.168.0.111`, port `23456`.

```bash
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
  -M -m nrepl.cmdline --connect --host 192.168.0.111 --port 23456
```

Or run the automated Python test scripts (see `tests/`).

## ObjC interop

The key namespace is `grease.ios.objc`. It is pre-loaded into the SCI
context at app startup. Two public functions:

```clojure
;; Allocate a new instance of an ObjC class (by pointer or string name)
(objc-rt/new-instance LocationDelegate)
(objc-rt/new-instance "NSString")

;; Send an ObjC message (use instead of the `objc` macro from SCI nREPL —
;; SCI's reader rejects symbols with colons, so setDelegate: etc. fail)
(objc-rt/msg-send :void mgr "setDelegate:" :pointer d)
(objc-rt/msg-send :void mgr "startUpdatingLocation")
```

Define new ObjC classes at runtime:
```clojure
(objc-rt/defclass LocationDelegate "NSObject"
  "locationManager:didUpdateLocations:" "v@:@@"
  (fn [_self _cmd _mgr locs]
    (reset! last-location locs)))
```

Full working example: `dev/test_location.clj`.

## SCI limitations (important)

- **`objc` macro**: Works for zero- and one-part selectors. Fails with a
  reader error for any selector containing a colon in symbol position
  (`setDelegate:`, `addObject:`, etc.). Use `objc-rt/msg-send` instead.

- **Namespace aliases in macros**: SCI resolves syntax-quote symbols at
  expansion time in the *calling* namespace. Any macro that generates code
  must use fully-qualified names (`com.phronemophobic.grease/...`) inside
  backtick templates, not aliases.

- **Nested backtick in frozen SCI**: Do not use `~@(for [...] \`(...))` inside
  an outer backtick in any macro that will be loaded by SCI at native-image
  build time. SCI's gensym counter restarts between backtick levels in the
  frozen image, causing `cls__30__auto__` unresolved-symbol errors. Use
  `mapv` to compute forms outside the outer backtick instead.

- **ARC retention**: ObjC objects are released unless held by a Clojure
  atom. Always store delegates AND managers in atoms.

- **Main thread**: CLLocationManager, UIKit, and most delegate wiring must
  happen on the main thread. Use `grease/dispatch-main-async`.

## Test scripts

```bash
# Full integration test with location permission flow (20s GPS wait)
python3 tests/run_test_location.py

# Legacy step-by-step test including grease wrapper redefinition workaround
python3 tests/full_test_v2.py
```

## Key files

| Path | Purpose |
|------|---------|
| `src/grease/ios/objc.clj` | `defclass`, `msg-send`, `new-instance` |
| `src/com/phronemophobic/grease.clj` | SCI context setup, JVM FFI wrappers |
| `dev/test_location.clj` | CLLocationManager REPL integration test |
| `tests/run_test_location.py` | Automated nREPL runner for test_location |
| `tests/full_test_v2.py` | Legacy end-to-end with workarounds |
| `examples/objc/objc.clj` | ObjC interop cookbook |
| `conf/reflectionconfig-arm64-ios.json` | GraalVM reflection config |
| `scripts/build-bb-o` | Uberjar + native-image build |
| `xcode/MobileTest/` | iOS Xcode project |
| `.clj-kondo/config.edn` | Teaches kondo that `defclass` defines a var |

## Linting

After editing any `*.clj` file:
```bash
clj-kondo --lint <file>
cljfmt fix <file>
```
