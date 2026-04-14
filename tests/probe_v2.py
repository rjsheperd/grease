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

def send_eval(s, label, code, timeout=4):
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
    for v in vals: print(f"  => {v}")
    for e in err_msgs: print(f"  !! {e[:600]}")
    sys.stdout.flush()
    return ok, vals, err_msgs

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((HOST, PORT))
s.settimeout(15)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1); s.recv(4096)
print("Connected")

# Get full ex-data from register-objc-sel to see which argtype is bad
send_eval(s, "register-objc-sel full exception data", """
(try
  (com.phronemophobic.grease/register-objc-sel "new")
  (catch Exception e
    (str "type=" (class e) " msg=" (.getMessage e)
         " data=" (when (instance? clojure.lang.ExceptionInfo e) (ex-data e)))))
""")

# Also test get-objc-class — same pattern
send_eval(s, "get-objc-class full exception data", """
(try
  (com.phronemophobic.grease/get-objc-class "NSObject")
  (catch Exception e
    (str "type=" (class e) " msg=" (.getMessage e)
         " data=" (when (instance? clojure.lang.ExceptionInfo e) (ex-data e)))))
""")

# Test objc-new — uses register-objc-sel internally
send_eval(s, "objc-new NSObject", """
(try
  (com.phronemophobic.grease/objc-new
    (com.phronemophobic.grease/get-objc-class "NSObject"))
  (catch Exception e
    (str "FAIL: " (.getMessage e))))
""")

# Does register-objc-sel work if we call the underlying ffi/call ourselves
# (bypassing the compiled function)?
send_eval(s, "manual sel_registerName via SCI ffi/call", """
(do
  (require '[com.phronemophobic.clj-libffi :as ffi])
  (require '[tech.v3.datatype.ffi :as dt-ffi])
  (str (ffi/call "sel_registerName" :pointer :pointer (dt-ffi/string->c "new"))))
""")

# Redefine register-objc-sel in SCI to use the working pattern
send_eval(s, "redefine register-objc-sel in SCI", """
(do
  (require '[com.phronemophobic.clj-libffi :as ffi])
  (require '[tech.v3.datatype.ffi :as dt-ffi])
  (in-ns 'com.phronemophobic.grease)
  (defn register-objc-sel [selector-str]
    (ffi/call "sel_registerName" :pointer :pointer (dt-ffi/string->c selector-str)))
  (defn get-objc-class [class-name]
    (ffi/call "objc_getClass" :pointer :pointer (dt-ffi/string->c class-name)))
  (defn allocate-objc-class! [class-name superclass]
    (ffi/call "objc_allocateClassPair" :pointer
              :pointer superclass :pointer (dt-ffi/string->c class-name) :int64 0))
  (defn add-objc-method! [cls sel imp type-encoding]
    (ffi/call "class_addMethod" :int8
              :pointer cls :pointer sel :pointer imp
              :pointer (dt-ffi/string->c type-encoding)))
  (in-ns 'user)
  :redefined)
""", 5)

# Now test after redefine
send_eval(s, "register-objc-sel after redefine", """
(str (com.phronemophobic.grease/register-objc-sel "new"))
""")

# Now test defclass with the fixed wrappers
send_eval(s, "defclass after wrapper fix", """
(do
  (require '[grease.ios.objc :as objc-rt])
  (objc-rt/defclass TestDelegate "NSObject"
    "locationManager:didUpdateLocations:" "v@:@@"
    (fn [_self _cmd _mgr locs]
      (println "[Clojure] callback! locs=" locs)))
  (str "TestDelegate=" TestDelegate))
""", 6)

s.close()
print("\nDone.")
