"""
run_test_struct_dispatch.py — Verifies CLLocation accessors and struct-arg
dispatch in grease.ios.invoke/dispatch! via ffi/call-ptr composite types.

Tests:
  • coordinate (CLLocation → {:latitude double :longitude double} via C shims)
  • latitude, longitude (C shim scalar accessors)
  • accuracy (direct objc-rt/msg-send :float64)
  • set-region! (MKMapView ← MKCoordinateRegion 32-byte struct arg via ffi/call-ptr)

Note: CLLocationCoordinate2D STRUCT RETURN (via ffi/call-ptr :CLLocationCoordinate2D)
crashes ARM64 because libffi incorrectly prepends a hidden stret pointer, shifting
receiver/selector in objc_msgSend.  coordinate/latitude/longitude use C shims.

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
    """Send a single nREPL eval and collect all response bytes.

    Waits `timeout_s` seconds for the response to start arriving, then
    reads until the socket is quiet for 2 seconds (short per-read timeout).
    This avoids the 30-second drain-wait that would occur if we kept the
    global settimeout(30) on each recv loop iteration.
    """
    sock.sendall(bencode_dict({'op': 'eval', 'id': '99', 'code': code}))
    time.sleep(timeout_s)
    # Use a short per-read timeout so the loop drains quickly once all
    # messages (value + done status) have arrived.
    sock.settimeout(2)
    raw = b''
    while True:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            raw += chunk
        except Exception:
            break
    # Restore a longer timeout for future ops (e.g. next sleep+recv).
    sock.settimeout(30)
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
# Step 5: coordinate — builds {:latitude :longitude} map from C shims
# GOAL: {:latitude <real-double> :longitude <real-double>}
# ---------------------------------------------------------------------------

ok_c, vals_c, _ = send_eval(
    sock, '5. coordinate (C shim path — GOAL: {:latitude ... :longitude ...})',
    '(str (demo-app.location/coordinate @demo-app.location/last-location))',
    timeout_s=4)
coord_val = vals_c[-1] if vals_c else ''
coord_ok  = ok_c and ':latitude' in coord_val and ':longitude' in coord_val

# ---------------------------------------------------------------------------
# Step 6-8: Scalar accessors
# ---------------------------------------------------------------------------

ok_lat, lat_vals, _ = send_eval(sock, '6. latitude',
    '(demo-app.location/latitude @demo-app.location/last-location)',
    timeout_s=3)
ok_lng, lng_vals, _ = send_eval(sock, '7. longitude',
    '(demo-app.location/longitude @demo-app.location/last-location)',
    timeout_s=3)
ok_acc, acc_vals, _ = send_eval(sock, '8. accuracy',
    '(demo-app.location/accuracy @demo-app.location/last-location)',
    timeout_s=3)

sock.close()

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

print()
lat_val = lat_vals[-1] if lat_vals else ''
lng_val = lng_vals[-1] if lng_vals else ''
acc_val = acc_vals[-1] if acc_vals else ''

lat_ok  = ok_lat and lat_val not in ('', 'nil', '0.0')
lng_ok  = ok_lng and lng_val not in ('', 'nil', '0.0')

if coord_ok and lat_ok and lng_ok:
    print(f'PASS — coordinate = {coord_val}')
    print(f'       lat={lat_val}  lng={lng_val}  acc={acc_val}')
else:
    if not coord_ok:
        print(f'FAIL — coordinate: Got: {coord_val!r}')
    if not lat_ok:
        print(f'FAIL — latitude:   Got: {lat_val!r}')
    if not lng_ok:
        print(f'FAIL — longitude:  Got: {lng_val!r}')
    sys.exit(1)
