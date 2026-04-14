import socket, time, sys

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

def send_eval(s, label, code, timeout=6):
    msg = bencode_dict({'op': 'eval', 'id': '99', 'code': code})
    s.sendall(msg)
    time.sleep(timeout)
    resp = b''
    while True:
        try:
            chunk = s.recv(8192)
            if not chunk: break
            resp += chunk
        except: break
    result = resp.decode('utf-8', errors='replace')
    print(f"\n=== {label} ===", flush=True)
    print(result[:1200], flush=True)
    sys.stdout.flush()
    return result

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((HOST, PORT))
s.settimeout(15)
msg = bencode_dict({'op': 'clone', 'id': '1'})
s.sendall(msg)
time.sleep(1)
s.recv(4096)
print("Connected to nREPL", flush=True)

# Step 1: Redefine broken FFI wrappers
send_eval(s, "1. Fix FFI wrappers", """
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
      :pointer superclass :pointer (tech.v3.datatype.ffi/string->c class-name) :int64 0))
  (defn add-objc-method! [cls sel imp type-encoding]
    (com.phronemophobic.clj-libffi/call
      "class_addMethod" :int8
      :pointer cls :pointer sel :pointer imp
      :pointer (tech.v3.datatype.ffi/string->c type-encoding)))
  (in-ns 'user)
  :wrappers-fixed)
""", 4)

# Step 2: Fix defclass macro
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
               `(let [~extra-sym (parse-extra-arg-types ~enc)
                      ~ret-sym   (ret-type-from-encoding ~enc)
                      ~imp-sym   (grease/make-imp ~f ~extra-sym ~ret-sym)
                      ~sel-sym   (grease/register-objc-sel ~sel-str)]
                  (grease/add-objc-method! ~cls-sym ~sel-sym ~imp-sym ~enc))))
         (grease/register-objc-class! ~cls-sym)
         (def ~class-name ~cls-sym))))
  (in-ns 'user)
  :macro-fixed)
""", 4)

# Step 3: Define state atoms and msg-send helper
send_eval(s, "3. Define atoms + msg-send", """
(do
  (def last-location (atom nil))
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
(require '[grease.ios.objc :as objc-rt])
(objc-rt/defclass LocationDelegate "NSObject"
  "locationManager:didUpdateLocations:" "v@:@@"
  (fn [self _cmd mgr locs]
    (println "[Clojure] locationManager:didUpdateLocations: called! locs=" locs)
    (reset! last-location locs)))
""", 6)

# Step 5: Start CLLocationManager on main thread
send_eval(s, "5. Start CLLocationManager", """
(com.phronemophobic.grease/dispatch-main-async
  (fn []
    (try
      (let [cls-mgr (com.phronemophobic.grease/get-objc-class "CLLocationManager")
            mgr     (com.phronemophobic.grease/objc-new cls-mgr)
            d       (grease.ios.objc/new-instance LocationDelegate)]
        (reset! held-delegate d)
        (msg-send :void mgr "setDelegate:" :pointer d)
        (msg-send :void mgr "requestWhenInUseAuthorization")
        (msg-send :void mgr "startUpdatingLocation")
        (println "[Clojure] CLLocationManager: delegate set, auth requested, updates started"))
      (catch Exception e
        (println "[Clojure] SETUP ERROR:" (class e) (.getMessage e))))))
:dispatched
""", 6)

# Step 6: Check held-delegate (should be non-nil if dispatch ran)
send_eval(s, "6. held-delegate (non-nil = dispatch ran)", "(str @held-delegate)", 3)

print("\n>>> Please tap 'Allow' on location permission dialog if it appeared <<<", flush=True)
print(">>> Waiting 10s for location updates...", flush=True)
time.sleep(10)

# Step 7: Check last-location
send_eval(s, "7. last-location (GOAL: non-nil)", "(str @last-location)", 3)

s.close()
print("\nDone.", flush=True)
