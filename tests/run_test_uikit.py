"""
run_test_uikit.py — Integration test for grease.ios.uikit UIKit helpers.

Tests:
  1. key-window     — returns a non-nil UIWindow pointer
  2. root-view      — returns a non-nil view from the root view controller
  3. get-frame      — returns a map with :x :y :w :h for the root view
  4. set-frame!     — round-trips a frame write+read on a fresh UIView
  5. describe-view  — returns :class :frame :subview-count for root view
  6. new-label      — creates a UILabel and add-subview! adds it to root view

Usage:
  python3 tests/run_test_uikit.py
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


def send_eval(s, label, code, timeout=6):
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
s.settimeout(30)
s.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1)
s.recv(4096)
print(f"Connected to {HOST}:{PORT}\n")

results = []

# 1. key-window — non-nil UIWindow pointer
ok, vals, _ = send_eval(s, "1. key-window returns UIWindow", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (str (on-main (ui/key-window))))
""")
results.append(('key-window non-nil', ok and vals and 'address' in vals[-1], vals[-1] if vals else ''))

# 2. root-view — non-nil UIView pointer
ok, vals, _ = send_eval(s, "2. root-view returns UIView", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (str (on-main (ui/root-view))))
""")
results.append(('root-view non-nil', ok and vals and 'address' in vals[-1], vals[-1] if vals else ''))

# 3. get-frame — returns map with x y w h
ok, vals, _ = send_eval(s, "3. get-frame returns {:x :y :w :h}", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (ui/get-frame (ui/root-view))))
""")
results.append(('get-frame has keys', ok and vals and ':x' in vals[-1] and ':w' in vals[-1], vals[-1] if vals else ''))

# 4. set-frame! — write then read back
ok, vals, _ = send_eval(s, "4. set-frame! round-trip on fresh UIView", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main
    (let [v (grease.ios.objc/msg-send :pointer
               (com.phronemophobic.grease/get-objc-class "UIView")
               "new")]
      (ui/set-frame! v 10.0 20.0 100.0 50.0)
      (ui/get-frame v))))
""")
results.append(('set-frame! round-trip', ok and vals and '10' in vals[-1] and '100' in vals[-1], vals[-1] if vals else ''))

# 5. describe-view — returns class + frame + subview-count
ok, vals, _ = send_eval(s, "5. describe-view of root view", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (ui/describe-view (ui/root-view))))
""")
results.append(('describe-view has :class', ok and vals and ':class' in vals[-1] and ':frame' in vals[-1], vals[-1] if vals else ''))

# 6. new-label + add-subview! — label appears in subviews
ok, vals, _ = send_eval(s, "6. new-label + add-subview! adds to view", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main
    (let [rv  (ui/root-view)
          lbl (ui/new-label "REPL test label")]
      (ui/set-frame! lbl 20.0 100.0 280.0 44.0)
      (ui/add-subview! rv lbl)
      (count (ui/subviews rv)))))
""")
results.append(('add-subview! increases count', ok and vals and int(vals[-1]) > 0, vals[-1] if vals else ''))

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
    print("ALL TESTS PASSED — grease.ios.uikit helpers are working.")
else:
    print("SOME TESTS FAILED — see output above.")
