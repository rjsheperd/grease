# ARM64 iOS: struct return (HFA) and struct-by-value arg via ffi_call crash objc_msgSend

**Target repo:** https://github.com/libffi/libffi

---

## Summary

When using libffi (v3.5.2) to call Objective-C methods on ARM64 iOS via
`objc_msgSend`, two categories of struct types cause either incorrect results
or crashes:

1. **Struct return** — methods returning an HFA struct (e.g.
   `CLLocationCoordinate2D = {double, double}`) produce a crash consistent with
   the receiver/selector arguments being shifted.
2. **Struct by-value argument** — methods taking a large struct argument (e.g.
   `MKCoordinateRegion = {CLLocationCoordinate2D, MKCoordinateSpan}`, 32 bytes)
   produce a crash at the call site.

In both cases the workaround is a C shim that makes the ObjC call directly from
native code and returns/accepts scalar components instead.

---

## Environment

- **libffi version:** 3.5.2 (vendored at `third_party/libffi-3.5.2`)
- **Architecture:** `aarch64-apple-ios` (physical device, arm64)
- **Caller:** `com.phronemophobic/clj-libffi` (Clojure/JVM wrapper, JNA-based)
- **Platform:** iOS 17–18, Apple silicon (M-series Mac host)

---

## Struct return case — CLLocationCoordinate2D

`CLLocationCoordinate2D` is:

```c
typedef struct {
    CLLocationDegrees latitude;   // double
    CLLocationDegrees longitude;  // double
} CLLocationCoordinate2D;
```

16 bytes, two consecutive `double` fields — a valid ARM64 AAPCS HFA. Per the
AAPCS, an HFA with ≤ 4 members is returned in `v0`–`v3` (here: `d0` and `d1`).
No hidden stret pointer / x8 should be involved.

When this struct is used as the return type of an `ffi_cif` and the CIF is used
to call `objc_msgSend`, the call crashes with `EXC_BAD_ACCESS` at
`objc_msgSend+32`. The crash pattern is consistent with the hidden-pointer (x8
stret) path being taken even though the struct qualifies as HFA, causing the
receiver (`self` in x0) and selector (`SEL` in x1) to be interpreted
incorrectly.

**Reproduction call:**

```c
// CLLocation *loc = ...;
// [loc coordinate]  returns CLLocationCoordinate2D — crashes via libffi
```

**Workaround (C shim):**

```c
double grease_location_latitude(void *location) {
    return ((__bridge CLLocation *)location).coordinate.latitude;
}
double grease_location_longitude(void *location) {
    return ((__bridge CLLocation *)location).coordinate.longitude;
}
```

These C shims are called individually via `ffi_call` with scalar return types,
bypassing the struct return path entirely. The same pattern was required for
`CGRect`, `CGPoint`, `CGSize`, and `UIScreen.bounds` — all decomposed into
scalar shims.

---

## Struct-by-value argument case — MKCoordinateRegion

`MKCoordinateRegion` is:

```c
typedef struct {
    CLLocationCoordinate2D center;  // {double, double}
    MKCoordinateSpan       span;    // {double, double}
} MKCoordinateRegion;
```

32 bytes, four consecutive `double` fields. Per AAPCS this is also an HFA (4
doubles), passed in `v0`–`v3`. The call:

```objc
[mapView setRegion:region animated:YES]
```

takes `MKCoordinateRegion` by value as the first non-self/SEL argument.

When passed as a struct argument through libffi, the call crashes. Workaround:

```c
void set_map_region(void *mapView,
                           double center_lat, double center_lng,
                           double span_lat_delta, double span_lng_delta,
                           int animated) {
    MKMapView *mv = (__bridge MKMapView *)mapView;
    MKCoordinateRegion r =
        MKCoordinateRegionMake(
            CLLocationCoordinate2DMake(center_lat, center_lng),
            MKCoordinateSpanMake(span_lat_delta, span_lng_delta));
    [mv setRegion:r animated:(BOOL)animated];
}
```

---

## libffi source analysis

In `src/aarch64/ffi.c`, `is_vfp_type()` correctly identifies HFAs via
`is_hfa0` / `is_hfa1`. For a struct return, `ffi_prep_cif_machdep` sets:

```c
flags = is_vfp_type(rtype);
if (flags == 0) {
    size_t s = rtype->size;
    if (s > 16) {
        flags = AARCH64_RET_VOID | AARCH64_RET_IN_MEM;
        bytes += 8;
    } else if (s == 16)
        flags = AARCH64_RET_INT128;
    ...
}
```

If `is_vfp_type` returns non-zero (HFA detected), `AARCH64_RET_IN_MEM` is
**not** set and x8 is not used as the stret register. In `src/aarch64/sysv.S`:

```asm
mov x8, x3  /* install structure return */
...
ldp x0, x1, [sp, ...]  /* load self and SEL from arg stack */
```

x8 is the AAPCS indirect-result register — separate from argument registers
x0–x7 — so setting it should not shift self or SEL.

**Question for maintainers:** Is there a known interaction between `ffi_call`
HFA struct returns and `objc_msgSend` on `aarch64-apple-ios` that is not
handled by the current code? Or is the issue more likely in how `clj-libffi`
(JNA-based, JVM) constructs the `ffi_type` elements array for these struct
types — e.g. not populating `elements` correctly, causing `is_vfp_type` to
return 0 and the call to fall into the `s == 16 → AARCH64_RET_INT128` path,
reading the result from x0/x1 instead of d0/d1?

---

## References

- ARM64 AAPCS §6.4.2 — Composite types and HFAs
- [clj-libffi](https://github.com/phronmophobic/clj-libffi) — the JVM wrapper in use
- [grease](https://github.com/phronmophobic/grease) — project using these shims (Clojure on iOS via GraalVM)
