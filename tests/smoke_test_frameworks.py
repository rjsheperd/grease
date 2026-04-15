"""
smoke_test_frameworks.py — ObjC class-accessibility smoke test for all scraped frameworks.

Reads class names from the EDN specs on disk (no device I/O needed for the
class list), then asks the on-device nREPL to call get-objc-class for each
one.  Reports per-framework pass/fail and a final summary.

A framework PASSES if ≥1 class is found and no Clojure exception was thrown.
A framework PARTIAL if some but not all classes are accessible.
A framework MISSING if zero classes are accessible (Swift-only, not linked,
  or entitlement-gated frameworks not present in the demo app).

Usage:
  python3 tests/smoke_test_frameworks.py [--host IP] [--port PORT] [-v]

Defaults: host=192.168.0.111, port=23456
"""

import os, socket, time, sys, re, argparse

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SPECS_DIR = os.path.join(ROOT, "resources", "grease", "api-specs")
MANIFEST   = os.path.join(SPECS_DIR, "manifest.edn")

# ---------------------------------------------------------------------------
# CLI args
# ---------------------------------------------------------------------------

parser = argparse.ArgumentParser(description="Framework smoke test")
parser.add_argument("--host", default="192.168.0.111")
parser.add_argument("--port", type=int, default=23456)
parser.add_argument("--timeout", type=int, default=120,
                    help="Seconds to wait for the batch eval (default 120)")
parser.add_argument("--verbose", "-v", action="store_true",
                    help="Print missing class names per framework")
parser.add_argument("--framework", "-f", metavar="NAME",
                    help="Smoke-test only this framework (substring match)")
args = parser.parse_args()

HOST    = args.host
PORT    = args.port
TIMEOUT = args.timeout

# ---------------------------------------------------------------------------
# Step 1: parse EDN specs from disk
# ---------------------------------------------------------------------------

def parse_manifest(path):
    """Extract spec paths from manifest.edn using regex."""
    with open(path) as f:
        text = f.read()
    return re.findall(r'"(grease/api-specs/[^"]+\.edn)"', text)

def parse_spec(path):
    """
    Extract framework name and ObjC class names from a spec EDN file.
    Uses simple regex — good enough for the generated format.
    """
    with open(path) as f:
        text = f.read()
    fw_m = re.search(r':framework\s+"([^"]+)"', text)
    fw_name = fw_m.group(1) if fw_m else os.path.basename(path).replace(".edn", "")
    # :name entries inside :classes vector
    # We match all :name "Foo" pairs; a few false positives from :methods are
    # OK — non-class strings just return nil from get-objc-class.
    classes = re.findall(r':name\s+"([^"]+)"', text)
    # De-duplicate while preserving order; drop obvious non-class strings
    seen, unique = set(), []
    for cls in classes:
        # Skip obvious non-ObjC identifiers (contain spaces, start lower, etc.)
        if cls not in seen and cls and cls[0].isupper() and " " not in cls:
            seen.add(cls)
            unique.append(cls)
    return fw_name, unique

print("Reading specs from disk …")
spec_paths = parse_manifest(MANIFEST)
frameworks = []  # list of (fw_name, [class_names])
for rel_path in spec_paths:
    abs_path = os.path.join(ROOT, "resources", rel_path)
    if not os.path.exists(abs_path):
        continue
    fw_name, classes = parse_spec(abs_path)
    if args.framework and args.framework.lower() not in fw_name.lower():
        continue
    if classes:
        frameworks.append((fw_name, classes))

print(f"Loaded {len(frameworks)} frameworks, "
      f"{sum(len(c) for _, c in frameworks)} total class names.\n")

if not frameworks:
    print("No frameworks matched — check --framework filter or manifest path.")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Step 2: nREPL helpers
# ---------------------------------------------------------------------------

def bencode_str(s):
    b = s.encode("utf-8")
    return str(len(b)).encode() + b":" + b

def bencode_dict(d):
    items = b"d"
    for k, v in sorted(d.items()):
        items += bencode_str(k)
        items += bencode_str(v)
    items += b"e"
    return items

def recv_until_done(sock, timeout_s):
    sock.settimeout(2.0)
    raw = b""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
            if chunk:
                raw += chunk
                if b"4:done" in raw:
                    time.sleep(0.3)
                    try:
                        raw += sock.recv(65536)
                    except Exception:
                        pass
                    break
        except socket.timeout:
            continue
        except Exception:
            break
    return raw

def parse_response(raw):
    text = raw.decode("utf-8", errors="replace")
    values, errors = [], []
    for vlen in re.findall(r"5:value(\d+):", text):
        start = text.find(f"5:value{vlen}:") + len(f"5:value{vlen}:")
        values.append(text[start : start + int(vlen)])
    for elen in re.findall(r"3:err(\d+):", text):
        start = text.find(f"3:err{elen}:") + len(f"3:err{elen}:")
        errors.append(text[start : start + int(elen)])
    return values, errors

def send_eval(sock, code, timeout_s):
    sock.sendall(bencode_dict({"op": "eval", "id": "99", "code": code}))
    raw = recv_until_done(sock, timeout_s)
    return parse_response(raw)

# ---------------------------------------------------------------------------
# Step 3: connect
# ---------------------------------------------------------------------------

print(f"Connecting to {HOST}:{PORT} …")
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect((HOST, PORT))
sock.settimeout(10)
sock.sendall(bencode_dict({"op": "clone", "id": "1"}))
time.sleep(1)
sock.recv(4096)
print("Connected.\n")

# ---------------------------------------------------------------------------
# Step 4: build one big Clojure call with all class names inlined
# ---------------------------------------------------------------------------
# We pass a literal Clojure map  {"FrameworkName" ["Cls1" "Cls2" ...] ...}
# and probe each class with get-objc-class.

def clj_string_vec(names):
    return "[" + " ".join(f'"{n}"' for n in names) + "]"

fw_map_entries = "\n".join(
    f'  "{fw}" {clj_string_vec(classes)}'
    for fw, classes in frameworks
)

PROBE_CODE = f"""
(let [fw-classes {{"placeholder" []}}]
  (into (sorted-map)
    (for [[fw classes]
          {{{fw_map_entries}}}]
      (let [results (for [cls classes]
                      (let [found?
                            (try
                              (boolean
                                (com.phronemophobic.grease/get-objc-class cls))
                              (catch Exception _ false))]
                        [cls found?]))]
        [fw {{:total (count results)
              :found (count (filter second results))
              :missing (mapv first (remove second results))}}]))))
"""

print(f"Probing {len(frameworks)} frameworks (timeout {TIMEOUT}s) …\n")
vals, errs = send_eval(sock, PROBE_CODE, timeout_s=TIMEOUT)
sock.close()

if errs or not vals:
    print("FATAL: probe eval failed.")
    if errs:
        print(errs[0][:600])
    sys.exit(1)

result_str = vals[-1]

# ---------------------------------------------------------------------------
# Step 5: parse the pr-str result
# ---------------------------------------------------------------------------

# Match each framework block: "Name" {:total N, :found M, :missing [...]}
# Key order inside the inner map is not guaranteed, so we extract each field
# separately from the block text.
fw_block_pattern = re.compile(
    r'"([^"]+)"\s+(\{[^}]*:missing\s*\[[^\]]*\][^}]*\})',
    re.DOTALL,
)

def extract_int(text, key):
    m = re.search(rf":{key}\s+(\d+)", text)
    return int(m.group(1)) if m else 0

def extract_missing(text):
    m = re.search(r":missing\s+\[([^\]]*)\]", text)
    if not m:
        return []
    return re.findall(r'"([^"]+)"', m.group(1))

results = []
for m in fw_block_pattern.finditer(result_str):
    fw_name = m.group(1)
    block   = m.group(2)
    total   = extract_int(block, "total")
    found   = extract_int(block, "found")
    missing = extract_missing(block)
    results.append((fw_name, total, found, missing))

if not results:
    print("WARNING: could not parse result map. Raw output (first 2000 chars):")
    print(result_str[:2000])
    sys.exit(1)

# ---------------------------------------------------------------------------
# Step 6: report
# ---------------------------------------------------------------------------

passed, partial, missing_list = [], [], []

print()
print("=" * 72)
print(f"  {'FRAMEWORK':<32} {'FOUND':>6} {'TOTAL':>6}   STATUS")
print("=" * 72)

for fw_name, total, found, missing in sorted(results):
    if total == 0:
        status = "EMPTY "
        missing_list.append(fw_name)
    elif found == 0:
        status = "MISS  "
        missing_list.append(fw_name)
    elif found < total:
        status = "PART  "
        partial.append(fw_name)
    else:
        status = "PASS  "
        passed.append(fw_name)
    bar = "█" * min(found, 20) + "░" * min(total - found, 20)
    print(f"  {fw_name:<32} {found:>6}/{total:<6}  {status}")
    if args.verbose and missing:
        for cls in missing[:8]:
            print(f"      - {cls}")
        if len(missing) > 8:
            print(f"      … and {len(missing)-8} more")

print("=" * 72)
print()
print(f"  PASS    : {len(passed):>3}  (all classes found)")
print(f"  PARTIAL : {len(partial):>3}  (≥1 class found; some missing)")
print(f"  MISSING : {len(missing_list):>3}  (zero classes accessible)")
print(f"  TOTAL   : {len(results):>3}  frameworks probed")
print()

if missing_list:
    print("Inaccessible frameworks (Swift-only, not linked, or entitlement-gated):")
    for fw in missing_list:
        print(f"  • {fw}")
    print()

accessible = len(passed) + len(partial)
if accessible == len(results):
    print("RESULT: ALL FRAMEWORKS ACCESSIBLE")
elif accessible > 0:
    print(f"RESULT: {accessible}/{len(results)} frameworks accessible — see MISSING list above.")
else:
    print("RESULT: NO FRAMEWORKS ACCESSIBLE — check nREPL connection and build.")
    sys.exit(1)
