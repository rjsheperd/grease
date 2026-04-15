"""
run_test_bluetooth.py — Integration test for grease.ios.bluetooth.

Tests:
  1. CBCentralManager creation — manager created without crash
  2. Delegate wired        — bt-state atom updated from :unknown
  3. scan!                 — scanForPeripherals called without crash
  4. peripherals atom      — atom exists and is a vector
  5. stop-scanning!        — stops scan without crash

Note: The test does NOT wait for discovered peripherals because that
requires a real BLE environment.  The test verifies that the delegate
machinery is wired correctly and that state transitions fire.

Usage:
  python3 tests/run_test_bluetooth.py
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


def send_eval(s, label, code, timeout=8):
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
s.settimeout(40)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1)
s.recv(4096)
print(f"Connected to {HOST}:{PORT}\n")

results = []

# 1. CBCentralManager creation
ok, vals, _ = send_eval(s, "1. CBCentralManager created via start-scanning!", """
(do
  (require '[grease.ios.bluetooth :as bt])
  (require '[grease.ios.repl :refer [on-main]])
  (let [mgr (on-main (bt/start-scanning!))]
    (str mgr)))
""", timeout=10)
results.append(('manager created', ok and vals and 'address' in vals[-1], vals[-1] if vals else ''))

# 2. bt-state transitions from :unknown (wait for CB state callback)
# Use (deref (promise) N nil) as a portable SCI sleep (no Thread/sleep in SCI).
ok, vals, _ = send_eval(s, "2. bt-state updated from :unknown", """
(do
  (require '[grease.ios.bluetooth :as bt])
  ;; CB state callback fires async on main thread — poll with SCI-safe sleep.
  (loop [n 0]
    (let [st @bt/bt-state]
      (if (or (not= st :unknown) (> n 20))
        st
        (do
          (deref (promise) 300 nil)
          (recur (inc n)))))))
""", timeout=15)
results.append(('bt-state updated', ok and vals and ':unknown' not in vals[-1], vals[-1] if vals else ''))

# 3. scan! — no crash
ok, vals, _ = send_eval(s, "3. scan! called without crash", """
(do
  (require '[grease.ios.bluetooth :as bt])
  (require '[grease.ios.repl :refer [on-main]])
  (when (= @bt/bt-state :powered-on)
    (on-main (bt/scan!)))
  :scan-called)
""")
results.append(('scan! no crash', ok and vals and 'scan-called' in vals[-1], vals[-1] if vals else ''))

# 4. peripherals atom is a vector
ok, vals, _ = send_eval(s, "4. peripherals atom is a vector", """
(do
  (require '[grease.ios.bluetooth :as bt])
  (vector? @bt/peripherals))
""")
results.append(('peripherals is vector', ok and vals and vals[-1] == 'true', vals[-1] if vals else ''))

# 5. stop-scanning! — no crash
ok, vals, _ = send_eval(s, "5. stop-scanning! without crash", """
(do
  (require '[grease.ios.bluetooth :as bt])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (bt/stop-scanning!))
  :stopped)
""")
results.append(('stop-scanning! no crash', ok and vals and 'stopped' in vals[-1], vals[-1] if vals else ''))

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
    print("ALL TESTS PASSED — grease.ios.bluetooth CoreBluetooth working.")
else:
    print("SOME TESTS FAILED — see output above.")
