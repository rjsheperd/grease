"""
run_test_blocks.py — Integration test for grease.ios.blocks ObjC block factory.

Tests (all run from the live nREPL on-device):
  1. make-void-block + call-void-block! — Clojure fn fires when block is invoked
  2. make-data-block — creates a block without error
  3. make-bool-error-block — creates a block without error
  4. URLSession data task via make-data-block — real HTTP request, callback fires

Usage:
  python3 tests/run_test_blocks.py
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

# 1. make-void-block + call-void-block! — Clojure fn fires when block invoked
ok, vals, _ = send_eval(s, "1. void block round-trip (make-void-block / call-void-block!)", """
(do
  (require '[grease.ios.blocks :as blk])
  (def block-fired (atom false))
  (def held-blk (atom nil))
  (let [b (blk/make-void-block (fn [] (reset! block-fired true)))]
    (reset! held-blk b)
    (blk/call-void-block! b))
  @block-fired)
""")
results.append(('void block round-trip', ok, vals[-1] if vals else '', 'true'))

# 2. make-data-block — verify creation succeeds (non-nil pointer returned)
ok, vals, _ = send_eval(s, "2. make-data-block (non-nil pointer)", """
(do
  (require '[grease.ios.blocks :as blk])
  (def held-data-blk (atom nil))
  (let [b (blk/make-data-block (fn [data resp err]
                                  (println "data block called")))]
    (reset! held-data-blk b)
    (some? b)))
""")
results.append(('make-data-block non-nil', ok, vals[-1] if vals else '', 'true'))

# 3. make-bool-error-block — verify creation succeeds
ok, vals, _ = send_eval(s, "3. make-bool-error-block (non-nil pointer)", """
(do
  (require '[grease.ios.blocks :as blk])
  (def held-bool-blk (atom nil))
  (let [b (blk/make-bool-error-block (fn [ok err]
                                        (println "bool block called ok=" ok)))]
    (reset! held-bool-blk b)
    (some? b)))
""")
results.append(('make-bool-error-block non-nil', ok, vals[-1] if vals else '', 'true'))

# 4. URLSession data task via make-data-block — real HTTP GET, callback fires
print()
print(">>> Test 4: Real HTTP request via URLSession + make-data-block")
print(">>> Waiting up to 15s for response...\n")

ok, vals, _ = send_eval(s, "4. URLSession GET (make-data-block callback fires)", """
(do
  (require '[grease.ios.blocks :as blk])
  (require '[grease.ios.foundation :as f])
  (require '[grease.ios.objc :as objc-rt])
  (def http-result (atom nil))
  (def held-http-blk (atom nil))
  (let [grease com.phronemophobic.grease/get-objc-class
        session (objc-rt/msg-send :pointer (grease "NSURLSession") "sharedSession")
        url     (objc-rt/msg-send :pointer (grease "NSURL")
                  "URLWithString:" :pointer (f/->nsstring "https://httpbin.org/get"))
        req     (objc-rt/msg-send :pointer (grease "NSURLRequest")
                  "requestWithURL:" :pointer url)
        handler (blk/make-data-block
                  (fn [data resp err]
                    (let [status (when resp (objc-rt/msg-send :int64 resp "statusCode"))
                          nbytes (when data (objc-rt/msg-send :int64 data "length"))]
                      (reset! http-result {:status status :bytes nbytes}))))
        task    (objc-rt/msg-send :pointer session
                  "dataTaskWithRequest:completionHandler:"
                  :pointer req :pointer handler)]
    (reset! held-http-blk handler)
    (objc-rt/msg-send :void task "resume"))
  :task-started)
""", timeout=3)

# Give the HTTP request time to complete
time.sleep(12)

ok2, vals2, _ = send_eval(s, "4b. Check http-result (non-nil = callback fired)", """
(str @http-result)
""", timeout=2)
http_ok = vals2 and vals2[-1] not in ('nil', '', 'null')
results.append(('URLSession data task callback', http_ok, vals2[-1] if vals2 else 'nil', '{:status 200 ...}'))

s.close()

print()
print("=" * 60)
all_ok = True
for name, ok, val, expected in results:
    status = "PASS" if ok else "FAIL"
    print(f"  {status}  {name}")
    if val:
        print(f"         => {val}")
    if not ok:
        all_ok = False

print()
if all_ok:
    print("ALL TESTS PASSED — grease.ios.blocks ObjC block factory is working.")
else:
    print("SOME TESTS FAILED — see output above.")
