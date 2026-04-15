"""
run_test_repl.py — Integration test for grease.ios.repl REPL helpers.

Tests:
  1. class-name-of — returns ObjC class name from a pointer
  2. respond-to?   — true for known selector, false for bogus one
  3. methods-of    — non-empty sorted list for NSString
  4. super-class-of / inheritance-chain — walks ObjC hierarchy
  5. on-main       — evaluates on main thread, returns result
  6. defclass!     — creates versioned class, safe to re-evaluate

Usage:
  python3 tests/run_test_repl.py
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
s.settimeout(20)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1)
s.recv(4096)
print(f"Connected to {HOST}:{PORT}\n")

results = []

# 1. class-name-of
ok, vals, _ = send_eval(s, "1. class-name-of (NSString instance)", """
(do
  (require '[grease.ios.repl :as repl])
  (require '[grease.ios.foundation :as f])
  (let [nsstr (f/->nsstring "hello")]
    (repl/class-name-of nsstr)))
""")
results.append(('class-name-of', ok and vals and 'NSTaggedPointerString' in vals[-1] or
                (ok and vals and 'NSString' in vals[-1]), vals[-1] if vals else ''))

# 2. respond-to? true and false
ok, vals, _ = send_eval(s, "2a. respond-to? true (NSString / length)", """
(do
  (require '[grease.ios.repl :as repl])
  (require '[grease.ios.foundation :as f])
  (let [nsstr (f/->nsstring "hello")]
    (repl/respond-to? nsstr "length")))
""")
results.append(('respond-to? true', ok, vals[-1] if vals else ''))

ok2, vals2, _ = send_eval(s, "2b. respond-to? false (NSString / nonexistentMethod)", """
(do
  (require '[grease.ios.repl :as repl])
  (require '[grease.ios.foundation :as f])
  (let [nsstr (f/->nsstring "hello")]
    (repl/respond-to? nsstr "nonexistentMethod12345")))
""")
results.append(('respond-to? false', ok2 and vals2 and vals2[-1] == 'false', vals2[-1] if vals2 else ''))

# 3. methods-of NSString — non-empty, contains known methods
ok, vals, _ = send_eval(s, "3. methods-of NSString (non-empty, contains length)", """
(do
  (require '[grease.ios.repl :as repl])
  (let [ms (repl/methods-of "NSString")]
    {:count (count ms) :has-length (some #{"length"} ms)}))
""")
results.append(('methods-of NSString', ok and vals and ':has-length "length"' in vals[-1], vals[-1] if vals else ''))

# 4. super-class-of and inheritance-chain
ok, vals, _ = send_eval(s, "4. super-class-of + inheritance-chain (UIViewController)", """
(do
  (require '[grease.ios.repl :as repl])
  {:super (repl/super-class-of "UIViewController")
   :chain (repl/inheritance-chain "UIViewController")})
""")
results.append(('super-class-of UIViewController', ok, vals[-1] if vals else ''))

# 5. on-main — evaluates on main thread
ok, vals, _ = send_eval(s, "5. on-main returns result from main thread", """
(do
  (require '[grease.ios.repl :as repl])
  (repl/on-main (+ 1 2)))
""")
results.append(('on-main result', ok and vals and vals[-1] == '3', vals[-1] if vals else ''))

# 6. defclass! — safe versioned re-eval
ok, vals, _ = send_eval(s, "6. defclass! creates versioned class", """
(do
  (require '[grease.ios.repl :as repl])
  (repl/defclass! ReplTestDelegate "NSObject"
    "testMethod" "v@:"
    (fn [_ _] (println "testMethod called")))
  (str ReplTestDelegate))
""")
results.append(('defclass! creates class', ok and vals and 'address' in vals[-1], vals[-1] if vals else ''))

# 6b. Re-eval defclass! — should create _v2 without crash
ok2, vals2, _ = send_eval(s, "6b. defclass! re-eval (creates _v2 safely)", """
(do
  (require '[grease.ios.repl :as repl])
  (repl/defclass! ReplTestDelegate "NSObject"
    "testMethod" "v@:"
    (fn [_ _] (println "testMethod v2")))
  (str ReplTestDelegate))
""")
results.append(('defclass! re-eval safe', ok2 and vals2 and 'address' in vals2[-1], vals2[-1] if vals2 else ''))

s.close()

print()
print("=" * 60)
all_ok = True
for name, ok, val in results:
    status = "PASS" if ok else "FAIL"
    print(f"  {status}  {name}")
    if val:
        print(f"         => {val[:120]}")
    if not ok:
        all_ok = False

print()
if all_ok:
    print("ALL TESTS PASSED — grease.ios.repl helpers are working.")
else:
    print("SOME TESTS FAILED — see output above.")
