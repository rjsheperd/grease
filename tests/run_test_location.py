"""
run_test_location.py — Loads dev/test_location.clj into the SCI nREPL and
verifies that CLLocationManager fires a location callback within 20 seconds.

Prerequisites:
  - App built and deployed with the latest bb.o (includes defclass + msg-send fixes)
  - App running on device with nREPL listening at HOST:PORT
  - Location permission granted (tap "Allow" when dialog appears)

Usage:
  python3 tests/run_test_location.py
"""
import os, socket, time, sys, re

HOST = '192.168.0.111'
PORT = 23456
LOCATION_WAIT_S = 20   # seconds to wait for the first GPS fix

# ---------------------------------------------------------------------------
# nREPL bencode helpers
# ---------------------------------------------------------------------------

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

def parse_response(raw):
    """Extract value strings and error strings from a raw bencode response."""
    text = raw.decode('utf-8', errors='replace')

    values = []
    for vlen in re.findall(r'5:value(\d+):', text):
        start = text.find(f'5:value{vlen}:') + len(f'5:value{vlen}:')
        values.append(text[start:start + int(vlen)])

    errors = []
    for elen in re.findall(r'3:err(\d+):', text):
        start = text.find(f'3:err{elen}:') + len(f'3:err{elen}:')
        errors.append(text[start:start + int(elen)])

    return values, errors

def send_eval(sock, label, code, timeout_s=5):
    sock.sendall(bencode_dict({'op': 'eval', 'id': '99', 'code': code}))
    time.sleep(timeout_s)
    raw = b''
    while True:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            raw += chunk
        except Exception:
            break
    vals, errs = parse_response(raw)
    ok = not errs
    tag = 'OK' if ok else 'FAIL'
    val_str = vals[-1][:300] if vals else '<nil>'
    print(f'[{tag}] {label}')
    if vals:
        print(f'       => {val_str}')
    if errs:
        print(f'       !! {errs[0][:400]}')
    sys.stdout.flush()
    return ok, vals, errs

# ---------------------------------------------------------------------------
# Connect
# ---------------------------------------------------------------------------

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect((HOST, PORT))
sock.settimeout(30)
sock.sendall(bencode_dict({'op': 'clone', 'id': '1'}))
time.sleep(1)
sock.recv(4096)
print(f'Connected to {HOST}:{PORT}\n')

# ---------------------------------------------------------------------------
# Step 1: Load dev/test_location.clj
# ---------------------------------------------------------------------------

here = os.path.dirname(os.path.abspath(__file__))
clj_path = os.path.join(here, '..', 'dev', 'test_location.clj')
with open(clj_path) as f:
    clj_code = f.read()

ok, _, _ = send_eval(sock, '1. Load dev/test_location.clj', clj_code, timeout_s=8)
if not ok:
    print('\nFATAL: failed to load test_location.clj — check the error above.')
    sock.close()
    sys.exit(1)

# ---------------------------------------------------------------------------
# Step 2: Start location updates
# ---------------------------------------------------------------------------

send_eval(sock, '2. start-location-updates!',
          '(dev.test-location/start-location-updates!)', timeout_s=5)

# ---------------------------------------------------------------------------
# Step 3: Check for dispatch error
# ---------------------------------------------------------------------------

send_eval(sock, '3. dispatch-error (nil = success)',
          '(str (dev.test-location/error))', timeout_s=2)

# ---------------------------------------------------------------------------
# Step 4: Check delegate was retained
# ---------------------------------------------------------------------------

send_eval(sock, '4. held-delegate (non-nil = dispatch ran)',
          '(str @dev.test-location/held-delegate)', timeout_s=2)

# ---------------------------------------------------------------------------
# Step 5: Wait for GPS fix
# ---------------------------------------------------------------------------

print(f'\n>>> Tap "Allow" on the location permission dialog if it appeared <<<')
print(f'>>> Waiting {LOCATION_WAIT_S}s for a GPS fix...\n')
time.sleep(LOCATION_WAIT_S)

# ---------------------------------------------------------------------------
# Step 6: Check last-location
# ---------------------------------------------------------------------------

ok, vals, _ = send_eval(sock, '5. last-location (GOAL: non-nil)',
                         '(str (dev.test-location/location))', timeout_s=2)

sock.close()

result = vals[-1] if vals else ''
print()
if result and result not in ('', 'nil', '""'):
    print(f'PASS — @last-location = {result}')
else:
    print('FAIL — @last-location is nil/empty')
    print('  Check step 3 (dispatch-error) for the root cause.')
    sys.exit(1)
