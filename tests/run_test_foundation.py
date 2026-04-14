"""
run_test_foundation.py — Integration test for grease.ios.foundation type bridge.

Tests (all run from the live nREPL on-device):
  1. NSString round-trip: ->nsstring / nsstring->str
  2. NSNumber round-trip: ->nsnumber-long / nsnumber->long
  3. NSNumber double: ->nsnumber-double / nsnumber->double
  4. NSArray round-trip: ->nsarray / nsarray->vec (via NSString elements)
  5. NSDictionary round-trip: ->nsdict-str / nsdict->map-str
  6. NSError: nserror->map returns expected keys

Usage:
  python3 tests/run_test_foundation.py
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


def send_eval(s, label, code, timeout=4):
    msg = bencode_dict({'op': 'eval', 'id': '99', 'code': code})
    s.sendall(msg)
    time.sleep(timeout)
    resp = b''
    while True:
        try:
            chunk = s.recv(65536)
            if not chunk:
                break
            resp += chunk
        except Exception:
            break
    result = resp.decode('utf-8', errors='replace')
    values = re.findall(r'5:value(\d+):', result)
    vals = []
    for vlen in values:
        start = result.find(f'5:value{vlen}:') + len(f'5:value{vlen}:')
        vals.append(result[start:start + int(vlen)])
    errs = re.findall(r'3:err(\d+):', result)
    err_msgs = []
    for elen in errs:
        start = result.find(f'3:err{elen}:') + len(f'3:err{elen}:')
        err_msgs.append(result[start:start + int(elen)])
    ok = not err_msgs
    status = 'OK' if ok else 'FAIL'
    val_str = vals[-1][:300] if vals else '<nil>'
    print(f"[{status}] {label}")
    if vals:
        print(f"       => {val_str}")
    if err_msgs:
        print(f"       !! {err_msgs[0][:400]}")
    sys.stdout.flush()
    return ok, vals, err_msgs


s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((HOST, PORT))
s.settimeout(15)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1)
s.recv(4096)
print(f"Connected to {HOST}:{PORT}\n")

results = []

# 1. NSString round-trip
ok, vals, _ = send_eval(s, "1. NSString round-trip (->nsstring / nsstring->str)", """
(do
  (require '[grease.ios.foundation :as f])
  (let [nsstr (f/->nsstring "hello from Clojure")]
    (f/nsstring->str nsstr)))
""")
results.append(('NSString round-trip', ok, vals[-1] if vals else '', '"hello from Clojure"'))

# 2. NSNumber long round-trip
ok, vals, _ = send_eval(s, "2. NSNumber long round-trip", """
(do
  (require '[grease.ios.foundation :as f])
  (let [n (f/->nsnumber-long 42)]
    (f/nsnumber->long n)))
""")
results.append(('NSNumber long', ok, vals[-1] if vals else '', '42'))

# 3. NSNumber double round-trip
ok, vals, _ = send_eval(s, "3. NSNumber double round-trip", """
(do
  (require '[grease.ios.foundation :as f])
  (let [n (f/->nsnumber-double 3.14)]
    (str (f/nsnumber->double n))))
""")
results.append(('NSNumber double', ok, vals[-1] if vals else '', '3.14'))

# 4. NSArray round-trip
ok, vals, _ = send_eval(s, "4. NSArray round-trip (->nsarray / nsarray->vec)", """
(do
  (require '[grease.ios.foundation :as f])
  (let [strings (mapv f/->nsstring ["alpha" "beta" "gamma"])
        arr     (f/->nsarray strings)
        back    (f/nsarray->vec arr)]
    (mapv f/nsstring->str back)))
""")
results.append(('NSArray round-trip', ok, vals[-1] if vals else '', '["alpha" "beta" "gamma"]'))

# 5. NSDictionary string round-trip
ok, vals, _ = send_eval(s, "5. NSDictionary string round-trip (->nsdict-str / nsdict->map-str)", """
(do
  (require '[grease.ios.foundation :as f])
  (let [d (f/->nsdict-str {"key1" "val1" "key2" "val2"})]
    (into (sorted-map) (f/nsdict->map-str d))))
""")
results.append(('NSDictionary round-trip', ok, vals[-1] if vals else '', '{\"key1\" \"val1\", \"key2\" \"val2\"}'))

# 6. NSError -> map (create an NSError via msg-send and convert it)
# Pass an empty NSDictionary (not nil) for userInfo to avoid clj-libffi null-pointer
# type error — ffi/call rejects java.lang.Long for :pointer args.
ok, vals, _ = send_eval(s, "6. NSError -> map (nserror->map)", """
(do
  (require '[grease.ios.foundation :as f])
  (require '[grease.ios.objc :as objc-rt])
  (let [domain     (f/->nsstring "com.grease.test")
        empty-dict (f/->nsdict {})
        msg-cls    (com.phronemophobic.grease/get-objc-class "NSError")
        err        (objc-rt/msg-send :pointer msg-cls
                     "errorWithDomain:code:userInfo:"
                     :pointer domain :int64 42 :pointer empty-dict)]
    (select-keys (f/nserror->map err) [:domain :code])))
""")
results.append(('NSError->map', ok, vals[-1] if vals else '', '{:code 42, :domain "com.grease.test"}'))

s.close()

print()
print("=" * 60)
all_ok = True
for name, ok, val, expected in results:
    status = "PASS" if ok else "FAIL"
    print(f"  {status}  {name}")
    if not ok:
        all_ok = False

print()
if all_ok:
    print("ALL TESTS PASSED — grease.ios.foundation type bridge is working.")
else:
    print("SOME TESTS FAILED — see output above.")
