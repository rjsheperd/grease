"""
full_test_v2.py — End-to-end test for examples/objc/objc.clj patterns.

Tests:
  1. Fix grease FFI wrappers (register-objc-sel, get-objc-class, allocate-objc-class!,
     add-objc-method!, objc-new) — the native image has old :string argtypes.
  2. Fix defclass macro — gensym bug + fully-qualified helper refs for SCI compat.
  3. msg-send helper
  4. defclass LocationDelegate
  5. CLLocationManager start
  6. Check held-delegate (non-nil = dispatch ran without error)
  7. Check @last-location (non-nil = location callback fired)

Usage:
  python3 tests/full_test_v2.py
"""
import socket, time, sys, re

HOST = '192.168.0.111'
PORT = 23456

def bencode_str(s):
    b = s.encode('utf-8')
    return str(len(b)).encode() + b':' + b

def bencode_dict(d):
    items = b'd'
    for k, v in sorted(d.items()):
        items += bencode_str(k)
        items += bencode_str(v)
    items += b'e'
    return items

def send_eval(s, label, code, timeout=5):
    msg = bencode_dict({'op': 'eval', 'id': '99', 'code': code})
    s.sendall(msg)
    time.sleep(timeout)
    resp = b''
    while True:
        try:
            chunk = s.recv(65536)
            if not chunk: break
            resp += chunk
        except: break
    result = resp.decode('utf-8', errors='replace')
    values = re.findall(r'5:value(\d+):', result)
    vals = []
    for vlen in values:
        start = result.find(f'5:value{vlen}:') + len(f'5:value{vlen}:')
        vals.append(result[start:start+int(vlen)])
    errs = re.findall(r'3:err(\d+):', result)
    err_msgs = []
    for elen in errs:
        start = result.find(f'3:err{elen}:') + len(f'3:err{elen}:')
        err_msgs.append(result[start:start+int(elen)])
    ok = not err_msgs
    status = 'OK' if ok else 'FAIL'
    val_str = vals[-1][:200] if vals else '<nil>'
    print(f"[{status}] {label}")
    if vals: print(f"       => {val_str}")
    if err_msgs: print(f"       !! {err_msgs[0][:300]}")
    sys.stdout.flush()
    return ok, vals, err_msgs

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((HOST, PORT))
s.settimeout(20)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1); s.recv(4096)
print("Connected\n")

# Step 1: Fix broken grease FFI wrappers.
# The native image was compiled with :string argtypes; redefine all
# affected functions in the SCI com.phronemophobic.grease namespace.
send_eval(s, "1. Fix grease FFI wrappers + objc-new", """
(do
  (in-ns 'com.phronemophobic.grease)
  (defn register-objc-sel [selector-str]
    (com.phronemophobic.clj-libffi/call
      "sel_registerName" :pointer :pointer
      (tech.v3.datatype.ffi/string->c selector-str)))
  (defn get-objc-class [class-name]
    (com.phronemophobic.clj-libffi/call
      "objc_getClass" :pointer :pointer
      (tech.v3.datatype.ffi/string->c class-name)))
  (defn allocate-objc-class! [class-name superclass]
    (com.phronemophobic.clj-libffi/call
      "objc_allocateClassPair" :pointer
      :pointer superclass
      :pointer (tech.v3.datatype.ffi/string->c class-name)
      :int64 0))
  (defn add-objc-method! [cls sel imp type-encoding]
    (com.phronemophobic.clj-libffi/call
      "class_addMethod" :int8
      :pointer cls :pointer sel :pointer imp
      :pointer (tech.v3.datatype.ffi/string->c type-encoding)))
  ;; objc-new is JVM-compiled, so redefine in SCI to use the fixed register-objc-sel
  (defn objc-new [cls]
    (let [sel (register-objc-sel "new")]
      (com.phronemophobic.clj-libffi/call
        "objc_msgSend" :pointer :pointer cls :pointer sel)))
  (in-ns 'user)
  :wrappers-fixed)
""", 5)

# Step 2: Fix defclass macro — gensym bug + fully-qualified helper function refs.
# SCI macros resolve syntax-quote symbols in the calling namespace at expand time,
# so parse-extra-arg-types / ret-type-from-encoding need full ns qualification.
send_eval(s, "2. Fix defclass macro", """
(do
  (in-ns 'grease.ios.objc)
  (defmacro defclass
    [class-name superclass & method-specs]
    ;; NOTE: All syntax-quote references must be fully-qualified.
    ;; SCI resolves syntax-quote symbols at expansion time in the CALLING namespace,
    ;; not the definition namespace. Aliases like grease/ won't exist in user ns.
    ;;
    ;; NOTE: method-forms is computed BEFORE the outer backtick to avoid nested
    ;; backtick context. In a frozen GraalVM native image, SCI's gensym counter
    ;; restarts at runtime, so ~cls-sym inside a nested backtick resolves to a
    ;; different symbol than the outer let binding. Computing method-forms in
    ;; plain code first means the inner backtick is only one level deep.
    (let [cls-sym (gensym "cls")
          method-forms
          (mapv (fn [[sel-str enc f]]
                  (let [extra-sym (gensym "extra-types")
                        ret-sym   (gensym "ret-type")
                        imp-sym   (gensym "imp")
                        sel-sym   (gensym "sel")]
                    `(let [~extra-sym (grease.ios.objc/parse-extra-arg-types ~enc)
                           ~ret-sym   (grease.ios.objc/ret-type-from-encoding ~enc)
                           ~imp-sym   (com.phronemophobic.grease/make-imp ~f ~extra-sym ~ret-sym)
                           ~sel-sym   (com.phronemophobic.grease/register-objc-sel ~sel-str)]
                       (com.phronemophobic.grease/add-objc-method!
                        ~cls-sym ~sel-sym ~imp-sym ~enc))))
                (partition 3 method-specs))]
      `(let [~cls-sym (com.phronemophobic.grease/allocate-objc-class!
                       ~(str class-name)
                       (com.phronemophobic.grease/get-objc-class ~superclass))]
         ~@method-forms
         (com.phronemophobic.grease/register-objc-class! ~cls-sym)
         (def ~class-name ~cls-sym))))
  (in-ns 'user)
  :macro-fixed)
""", 5)

# Step 3: Define state atoms, error capture, and msg-send helper.
send_eval(s, "3. Define atoms + msg-send + dispatch-error", """
(do
  (def last-location (atom nil))
  (def held-mgr (atom nil))
  (def held-delegate (atom nil))
  (def dispatch-error (atom nil))
  (defn msg-send [ret-type obj sel-str & typed-args]
    (let [sel (com.phronemophobic.grease/register-objc-sel sel-str)]
      (apply com.phronemophobic.clj-libffi/call
             "objc_msgSend" ret-type
             :pointer obj :pointer sel
             typed-args)))
  :state-defined)
""", 3)

# Step 4: defclass LocationDelegate.
send_eval(s, "4. defclass LocationDelegate", """
(do
  (require '[grease.ios.objc :as objc-rt])
  (objc-rt/defclass LocationDelegate "NSObject"
    "locationManager:didUpdateLocations:" "v@:@@"
    (fn [_self _cmd _mgr locs]
      (println "[Clojure] location update locs=" locs)
      (reset! last-location locs)))
  (str "LocationDelegate=" LocationDelegate))
""", 6)

# Step 5: Start CLLocationManager — capture any exception into dispatch-error atom.
send_eval(s, "5. Start CLLocationManager (with error capture)", """
(do
  (com.phronemophobic.grease/dispatch-main-async
    (fn []
      (try
        (let [cls-mgr (com.phronemophobic.grease/get-objc-class "CLLocationManager")
              _       (println "[dbg] cls-mgr=" (str cls-mgr))
              mgr     (com.phronemophobic.grease/objc-new cls-mgr)
              _       (println "[dbg] mgr=" (str mgr))
              d       (grease.ios.objc/new-instance LocationDelegate)
              _       (println "[dbg] delegate=" (str d))]
          (reset! held-mgr mgr)
          (reset! held-delegate d)
          (msg-send :void mgr "setDelegate:" :pointer d)
          (msg-send :void mgr "requestWhenInUseAuthorization")
          (msg-send :void mgr "startUpdatingLocation")
          (println "[Clojure] CLLocationManager started ok"))
        (catch Exception e
          (let [msg (str (class e) ": " (.getMessage e))]
            (reset! dispatch-error msg)
            (println "[Clojure] DISPATCH ERROR:" msg))))))
  :dispatched)
""", 6)

# Step 6: Check dispatch-error and held-delegate.
send_eval(s, "6a. dispatch-error (nil = success)", "(str @dispatch-error)", 2)
send_eval(s, "6b. held-delegate (non-nil = dispatch ran)", "(str @held-delegate)", 2)

print("\n>>> Tap 'Allow' on location permission dialog if it appeared <<<")
print(">>> Waiting 12s for location fix...\n")
time.sleep(12)

# Step 7: Final — last-location.
ok, vals, _ = send_eval(s, "7. last-location (GOAL: non-nil)", "(str @last-location)", 2)

s.close()
print()
result = vals[-1] if vals else ""
if result and result not in ('', 'nil', '""'):
    print(f"PASS — @last-location = {result}")
else:
    print(f"FAIL — @last-location nil/empty")
    print("      Check dispatch-error above for the root cause.")
