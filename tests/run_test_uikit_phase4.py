"""
run_test_uikit_phase4.py — Integration test for Phase 4 UIKit live manipulation.

Tests:
  1. set-text-color!     — sets label text color without error
  2. set-background-color! — sets view background color without error
  3. set-font-size!      — increases font size on an existing label
  4. set-text-alignment! — sets center alignment on a label
  5. view-tree           — returns nested map of the view hierarchy
  6. reactive atom       — add-watch on atom drives live UILabel text update

Usage:
  python3 tests/run_test_uikit_phase4.py
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

# Setup: create a shared label for styling tests
send_eval(s, "setup: create shared label", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (def test-label
    (on-main
      (let [lbl (ui/new-label "Phase 4 Test")]
        (ui/set-frame! lbl 20.0 200.0 280.0 60.0)
        (ui/add-subview! (ui/root-view) lbl)
        lbl)))
  :ok)
""", timeout=8)

# 1. set-text-color!
ok, vals, _ = send_eval(s, "1. set-text-color! (red)", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (ui/set-text-color! test-label 1.0 0.0 0.0 1.0))
  :color-set)
""")
results.append(('set-text-color!', ok and vals and 'color-set' in vals[-1], vals[-1] if vals else ''))

# 2. set-background-color!
ok, vals, _ = send_eval(s, "2. set-background-color! (yellow)", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (ui/set-background-color! test-label 1.0 1.0 0.0 0.8))
  :bg-set)
""")
results.append(('set-background-color!', ok and vals and 'bg-set' in vals[-1], vals[-1] if vals else ''))

# 3. set-font-size!
ok, vals, _ = send_eval(s, "3. set-font-size! (24pt)", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (ui/set-font-size! test-label 24.0))
  :font-set)
""")
results.append(('set-font-size!', ok and vals and 'font-set' in vals[-1], vals[-1] if vals else ''))

# 4. set-text-alignment! (center=1)
ok, vals, _ = send_eval(s, "4. set-text-alignment! (center)", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (on-main (ui/set-text-alignment! test-label 1))
  :align-set)
""")
results.append(('set-text-alignment!', ok and vals and 'align-set' in vals[-1], vals[-1] if vals else ''))

# 5. view-tree — shallow (depth 1 to avoid huge output)
ok, vals, _ = send_eval(s, "5. view-tree returns nested hierarchy", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (let [tree (on-main (ui/view-tree (ui/root-view) 1))]
    {:has-class (contains? tree :class)
     :has-children (contains? tree :children)
     :child-count (count (:children tree))}))
""", timeout=10)
results.append(('view-tree structure', ok and vals and ':has-class true' in vals[-1], vals[-1] if vals else ''))

# 6. Reactive atom drives UILabel text
# The watch dispatches to main thread; on-main also dispatches to main thread,
# so it is guaranteed to run AFTER the watch update (GCD serial queue ordering).
ok, vals, _ = send_eval(s, "6. reactive atom updates UILabel text", """
(do
  (require '[grease.ios.uikit :as ui])
  (require '[grease.ios.repl :refer [on-main]])
  (require '[grease.ios.foundation :as f])
  (def display-text (atom "initial"))
  (add-watch display-text :ui-sync
    (fn [_ _ _ new-val]
      (com.phronemophobic.grease/dispatch-main-async
        (fn []
          (grease.ios.objc/msg-send :void test-label
                                    "setText:" :pointer (f/->nsstring new-val))))))
  (reset! display-text "Live from REPL!")
  ;; on-main queues on the main GCD serial queue — after the watch's dispatch
  (on-main
    (f/nsstring->str (grease.ios.objc/msg-send :pointer test-label "text"))))
""", timeout=8)
results.append(('reactive atom updates label', ok and vals and 'Live from REPL!' in vals[-1], vals[-1] if vals else ''))

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
    print("ALL TESTS PASSED — Phase 4 UIKit live manipulation working.")
else:
    print("SOME TESTS FAILED — see output above.")
