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
    print(f"\n[{'OK' if ok else 'FAIL'}] {label}")
    for v in vals: print(f"  => {v[:400]}")
    for e in err_msgs: print(f"  !! {e[:400]}")
    sys.stdout.flush()
    return ok, vals, err_msgs

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((HOST, PORT))
s.settimeout(20)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1); s.recv(4096)
print("Connected")

# Step 1: Fix broken grease FFI wrappers (use fully-qualified names, no alias)
send_eval(s, "1. Fix grease FFI wrappers", """
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
  ;; objc-new is JVM-compiled and calls JVM register-objc-sel internally,
  ;; so redefine it in SCI too so it uses the fixed SCI register-objc-sel
  (defn objc-new [cls]
    (let [sel (register-objc-sel "new")]
      (com.phronemophobic.clj-libffi/call
        "objc_msgSend" :pointer :pointer cls :pointer sel)))
  (in-ns 'user)
  :wrappers-fixed)
""", 5)

# Step 2: Fix defclass macro — gensym fix + fully-qualified helper refs
send_eval(s, "2. Fix defclass macro", """
(do
  (in-ns 'grease.ios.objc)
  (defmacro defclass
    [class-name superclass & method-specs]
    (let [cls-sym (gensym "cls")]
      `(let [~cls-sym (grease/allocate-objc-class! ~(str class-name)
                                                    (grease/get-objc-class ~superclass))]
         ~@(for [[sel-str enc f] (partition 3 method-specs)]
             (let [extra-sym (gensym "extra-types")
                   ret-sym   (gensym "ret-type")
                   imp-sym   (gensym "imp")
                   sel-sym   (gensym "sel")]
               `(let [~extra-sym (grease.ios.objc/parse-extra-arg-types ~enc)
                      ~ret-sym   (grease.ios.objc/ret-type-from-encoding ~enc)
                      ~imp-sym   (grease/make-imp ~f ~extra-sym ~ret-sym)
                      ~sel-sym   (grease/register-objc-sel ~sel-str)]
                  (grease/add-objc-method! ~cls-sym ~sel-sym ~imp-sym ~enc))))
         (grease/register-objc-class! ~cls-sym)
         (def ~class-name ~cls-sym))))
  (in-ns 'user)
  :macro-fixed)
""", 5)

# Step 3: Define state atoms, msg-send helper
send_eval(s, "3. Define state atoms + msg-send", """
(do
  (def last-location (atom nil))
  (def held-mgr (atom nil))
  (def held-delegate (atom nil))
  (defn msg-send [ret-type obj sel-str & typed-args]
    (let [sel (com.phronemophobic.grease/register-objc-sel sel-str)]
      (apply com.phronemophobic.clj-libffi/call
             "objc_msgSend" ret-type
             :pointer obj :pointer sel
             typed-args)))
  :state-defined)
""", 3)

# Step 4: defclass LocationDelegate
send_eval(s, "4. defclass LocationDelegate", """
(do
  (require '[grease.ios.objc :as objc-rt])
  (objc-rt/defclass LocationDelegate "NSObject"
    "locationManager:didUpdateLocations:" "v@:@@"
    (fn [_self _cmd _mgr locs]
      (println "[Clojure] locationManager:didUpdateLocations: locs=" locs)
      (reset! last-location locs)))
  (str "LocationDelegate=" LocationDelegate))
""", 6)

# Step 5: Start CLLocationManager on main thread
send_eval(s, "5. Start CLLocationManager", """
(do
  (com.phronemophobic.grease/dispatch-main-async
    (fn []
      (try
        (let [cls-mgr (com.phronemophobic.grease/get-objc-class "CLLocationManager")
              mgr     (com.phronemophobic.grease/objc-new cls-mgr)
              d       (grease.ios.objc/new-instance LocationDelegate)]
          (reset! held-mgr mgr)
          (reset! held-delegate d)
          (msg-send :void mgr "setDelegate:" :pointer d)
          (msg-send :void mgr "requestWhenInUseAuthorization")
          (msg-send :void mgr "startUpdatingLocation")
          (println "[Clojure] CLLocationManager started"))
        (catch Exception e
          (println "[Clojure] ERROR:" (class e) (.getMessage e))))))
  :dispatched)
""", 6)

# Step 6: held-delegate (non-nil = dispatch ran)
send_eval(s, "6. held-delegate (non-nil = dispatch ran)", "(str @held-delegate)", 2)

print("\n>>> Tap 'Allow' on location permission dialog if it appeared <<<")
print(">>> Waiting 12s for location fix...")
time.sleep(12)

# Step 7: last-location
ok, vals, _ = send_eval(s, "7. last-location (GOAL: non-nil)", "(str @last-location)", 2)

s.close()
print()
result = vals[-1] if vals else ""
if result and result not in ('', 'nil'):
    print(f"PASS — @last-location = {result}")
else:
    print(f"FAIL — @last-location nil/empty")
