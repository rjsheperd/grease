"""
run_test_struct_dispatch.py — Verifies struct-return and struct-arg dispatch in
grease.ios.invoke/dispatch! via ffi/call-ptr composite types. No C shims.

Tests:
  • coordinate (CLLocation → CLLocationCoordinate2D struct return via d0/d1)
  • latitude, longitude, accuracy (derived from coordinate or direct msg-send)
  • set-region! (MKMapView ← MKCoordinateRegion 32-byte struct arg via ffi/call-ptr)

The key difference from run_test_location.py: this script verifies the struct
VALUES returned by coordinate (must be {:latitude ... :longitude ...}), proving
that ffi/call-ptr correctly unpacks HFA registers d0/d1 into a Clojure map.

Prerequisites:
  - App built and deployed with invoke.clj struct-dispatch changes (commit 06183b1+)
  - App running on device with nREPL at HOST:PORT
  - Location permission previously granted (no dialog expected on re-run)

Usage:
  python3 tests/run_test_struct_dispatch.py
"""
import os, socket, time, sys, re

HOST = '192.168.0.111'
PORT = 23456
LOCATION_WAIT_S = 20   # seconds to wait for the first GPS fix

# ---------------------------------------------------------------------------
# nREPL bencode helpers (identical to run_test_location.py)
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
    tag = 'OK' if ok else 'ERR'
    val_str = vals[-1][:300] if vals else '<no-value>'
    print(f'[{tag}] {label}')
    if vals:
        print(f'       => {val_str}')
    if errs:
        print(f'       !! {errs[0][:400]}')
    sys.stdout.flush()
    return ok, vals, errs

here = os.path.dirname(os.path.abspath(__file__))

def load_clj(sock, label, rel_path, timeout_s=8):
    """Read a .clj file and send its content as an eval payload (not load-file)."""
    path = os.path.join(here, '..', rel_path)
    with open(path) as f:
        code = f.read()
    return send_eval(sock, label, code, timeout_s=timeout_s)

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
# Step 1: Load dev/demo_app/location.clj
# ---------------------------------------------------------------------------

ok, _, _ = load_clj(sock, '1. load location.clj', 'dev/demo_app/location.clj')
if not ok:
    print('\nFATAL: failed to load location.clj — check the error above.')
    sock.close(); sys.exit(1)

# ---------------------------------------------------------------------------
# Step 2: Load dev/demo_app/map.clj
# ---------------------------------------------------------------------------

ok, _, _ = load_clj(sock, '2. load map.clj', 'dev/demo_app/map.clj')
if not ok:
    print('\nFATAL: failed to load map.clj — check the error above.')
    sock.close(); sys.exit(1)

# ---------------------------------------------------------------------------
# Step 3: Start location updates
# ---------------------------------------------------------------------------

send_eval(sock, '3. start-location-updates!',
          '(demo-app.location/start! (fn [_loc] nil))', timeout_s=5)

# ---------------------------------------------------------------------------
# Step 4: Wait for GPS fix
# ---------------------------------------------------------------------------

print(f'\n>>> Tap "Allow" on the location permission dialog if it appeared <<<')
print(f'>>> Waiting {LOCATION_WAIT_S}s for a GPS fix...\n')
time.sleep(LOCATION_WAIT_S)

# ---------------------------------------------------------------------------
# Step 5: Sanity — last-location non-nil
# ---------------------------------------------------------------------------

ok, vals, _ = send_eval(sock, '4. last-location (sanity)',
                        '(str @demo-app.location/last-location)', timeout_s=2)
if not ok or not vals or vals[-1] in ('', 'nil', '""'):
    print('\nFATAL: no GPS fix — cannot test struct dispatch')
    sock.close(); sys.exit(1)

# ---------------------------------------------------------------------------
# Step 6: coordinate — struct return (CLLocationCoordinate2D → Clojure map)
# GOAL: {:latitude <double> :longitude <double>}
# ---------------------------------------------------------------------------

ok_c, vals_c, _ = send_eval(
    sock, '5. coordinate (struct return — GOAL: {:latitude ... :longitude ...})',
    '(str (demo-app.location/coordinate @demo-app.location/last-location))',
    timeout_s=4)
coord_val = vals_c[-1] if vals_c else ''
coord_ok  = ok_c and ':latitude' in coord_val and ':longitude' in coord_val

# ---------------------------------------------------------------------------
# Step 7: Scalar helpers derived from struct return
# ---------------------------------------------------------------------------

send_eval(sock, '6. latitude',
          '(demo-app.location/latitude @demo-app.location/last-location)',
          timeout_s=3)
send_eval(sock, '7. longitude',
          '(demo-app.location/longitude @demo-app.location/last-location)',
          timeout_s=3)
send_eval(sock, '8. accuracy',
          '(demo-app.location/accuracy @demo-app.location/last-location)',
          timeout_s=3)

# ---------------------------------------------------------------------------
# Step 8: set-region! — struct arg (MKCoordinateRegion → setRegion:animated:)
# Creates a minimal MKMapView on the main thread, calls set-region!, checks
# no exception thrown.  GOAL: "dispatched"
# ---------------------------------------------------------------------------

send_eval(sock, '9. set-region! (struct arg — GOAL: dispatched)',
          '''(grease.ios.repl/on-main
               (let [mv (grease.ios.objc/msg-send :pointer
                           (grease.ios.objc/get-class "MKMapView") "new")]
                 (demo-app.map/set-region! mv 37.3346 -122.0090 0.05 0.05 false)
                 "dispatched"))''',
          timeout_s=5)

sock.close()

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

print()
if coord_ok:
    print(f'PASS — coordinate (struct return) = {coord_val}')
else:
    print(f'FAIL — coordinate did not return {{:latitude ... :longitude ...}}')
    print(f'       Got: {coord_val}')
    sys.exit(1)
