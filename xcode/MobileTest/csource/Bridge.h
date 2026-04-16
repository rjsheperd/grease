//
//  Bridge.h
//  MobileTest
//

#ifndef Bridge_h
#define Bridge_h

#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

// Log buffer / stdout capture
void        bridge_setup_stdout_capture(void);
const char *bridge_get_logs(void);

// Clojure entry-point wrappers
void           clj_log(const char *msg);
long long int  call_sub(long long int a, long long int b);
long long int  call_add(long long int a, long long int b);
void           call_print(const char *s);
void           call_prn(long long int id);
void           call_start_server(void);
long long int  call_eval(const char *s);
void           call_print_hi(void);
long long int  call_nrepl_port(void);
long long int  call_hash_code(void);

// ObjC helpers
long long int  objc_msgSendU64(const char *s);
void          *objc_make_string(const char *s);
void          *objc_make_selector(const char *s);

// Block factory shims — wrap a plain C function pointer in a heap ObjC block.
// The fn pointer must come from clj-libffi make-callback (ffi_closure trampoline).
// Returned pointer is +1 retained; hold in a Clojure atom to prevent early GC.
typedef void (*GreaseVoidFn)(void);
typedef void (*GreaseDataFn)(void *data, void *response, void *error);
typedef void (*GrallBoolErrFn)(int success, void *error);
typedef void (*Grease1PtrFn)(void *a);
typedef void (*Grease2PtrFn)(void *a, void *b);
typedef void (*Grease4PtrFn)(void *a, void *b, void *c, void *d);

void *grease_make_void_block(GreaseVoidFn fn);
void *grease_make_data_block(GreaseDataFn fn);
void *grease_make_bool_error_block(GrallBoolErrFn fn);
void *grease_make_1ptr_block(Grease1PtrFn fn);
void *grease_make_2ptr_block(Grease2PtrFn fn);
void *grease_make_4ptr_block(Grease4PtrFn fn);

// Testing helper: invoke a void block by its opaque pointer.
void  grease_call_void_block(void *block);

// Introspection — returns a +1-retained NSArray<NSString*> of selector names
// for all methods registered on cls (direct methods only, not inherited).
void *grease_class_method_names(void *cls);

// Null pointer and main GCD queue shims.
// clj-libffi's PToPointer protocol has no implementation for Clojure nil or
// java.lang.Long, so passing nil/0 as a :pointer arg throws at runtime.
// Use (f/null-ptr) for nil ObjC pointer args; (f/main-queue) for dispatch_queue_t.
void *grease_null_ptr(void);
void *grease_main_queue(void);

// UIKit frame / geometry shims — avoids CGRect struct return via FFI.
// All functions must be called on the main thread.
void   grease_set_frame(void *view, double x, double y, double w, double h);
double grease_get_frame_x(void *view);
double grease_get_frame_y(void *view);
double grease_get_frame_w(void *view);
double grease_get_frame_h(void *view);
void   grease_set_center(void *view, double cx, double cy);
double grease_get_center_x(void *view);
double grease_get_center_y(void *view);

#ifdef __cplusplus
}
#endif

#endif /* Bridge_h */
