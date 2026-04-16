//
//  Bridge.m
//  MobileTest
//

#include "Bridge.h"
#include "bb.h"
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <unistd.h>
#include <string.h>

// =============================================================================
// Isolate / thread globals
// =============================================================================

graal_isolate_t *isolate = NULL;
graal_isolatethread_t *thread = NULL;

// =============================================================================
// Log buffer — written by clj_log and the stdout pipe reader;
// read by bridge_get_logs() from Swift.
// =============================================================================

static NSMutableString *g_log_buf;
static NSLock          *g_log_lock;
static NSString        *g_log_snapshot; // keeps snapshot alive between Swift reads

static void bridge_init_log(void) {
    g_log_buf   = [NSMutableString new];
    g_log_lock  = [NSLock new];
}

static void bridge_append_raw(const char *msg) {
    NSString *s = [NSString stringWithUTF8String:(msg ?: "(null)")];
    [g_log_lock lock];
    [g_log_buf appendString:s];
    [g_log_buf appendString:@"\n"];
    [g_log_lock unlock];
}

// Called by Clojure via ffi/call (if dlsym finds it) and by Bridge functions.
void clj_log(const char *msg) {
    NSLog(@"[Clojure] %s", msg);
    bridge_append_raw(msg);
}

// Snapshot the buffer and return a stable C-string pointer valid until the
// next call to bridge_get_logs().
const char *bridge_get_logs(void) {
    [g_log_lock lock];
    g_log_snapshot = [g_log_buf copy];
    [g_log_lock unlock];
    return [g_log_snapshot UTF8String];
}

// =============================================================================
// Stdout pipe capture — redirects fd 1 so that Clojure's println (which writes
// to Java System.out → fd 1) lands in the log buffer AND is mirrored back to
// the original stdout for ios-deploy terminal output.
// =============================================================================

void bridge_setup_stdout_capture(void) {
    bridge_init_log();

    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) {
        NSLog(@"[Bridge] bridge_setup_stdout_capture: pipe() failed");
        return;
    }

    int tee_fd = dup(STDOUT_FILENO); // mirror to original stdout

    dup2(pipe_fds[1], STDOUT_FILENO);
    close(pipe_fds[1]);

    int read_fd = pipe_fds[0];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
        char buf[4096];
        ssize_t n;
        while ((n = read(read_fd, buf, sizeof(buf) - 1)) > 0) {
            buf[n] = '\0';
            // Mirror to original stdout (ios-deploy / Xcode console)
            if (tee_fd >= 0) {
                write(tee_fd, buf, (size_t)n);
            }
            // Strip trailing newlines before storing
            while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) {
                buf[--n] = '\0';
            }
            if (n > 0) {
                bridge_append_raw(buf);
            }
        }
    });
}

// =============================================================================
// Isolate-initialising wrappers (lazy — create isolate on first call)
// =============================================================================

static int ensure_isolate(void) {
    if (!isolate) {
        if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
            NSLog(@"[Bridge] graal_create_isolate failed");
            return 0;
        }
    }
    return 1;
}

long long int call_sub(long long int a, long long int b) {
    if (!ensure_isolate()) return 1;
    return clj_sub(thread, a, b);
}

long long int call_add(long long int a, long long int b) {
    if (!ensure_isolate()) return 1;
    return clj_add(thread, a, b);
}

void call_print(const char *s) {
    if (!ensure_isolate()) return;
    clj_print(thread, (void *)s);
}

void call_print_hi(void) {
    if (!ensure_isolate()) return;
    clj_print_hi(thread);
}

long long int call_eval(const char *s) {
    if (!ensure_isolate()) return -1;
    return clj_eval(thread, (void *)s);
}

void call_prn(long long int id) {
    if (!ensure_isolate()) return;
    clj_prn(thread, id);
}

// Returns the nREPL port Clojure has bound (0 = not started yet).
// dummy 0 arg: workaround for GraalVM not emitting IsolateEnterStub for () -> long.
long long int call_nrepl_port(void) {
    if (!isolate) return 0;
    return clj_nrepl_port(thread, 0);
}

// Returns the build-time hash of grease.clj as a 64-bit int.
// dummy 0 arg: same workaround as call_nrepl_port.
long long int call_hash_code(void) {
    if (!isolate) return 0;
    return clj_get_hash_code(thread, 0);
}

void call_start_server(void) {
    NSLog(@"[Bridge] call_start_server: entered");
    graal_isolatethread_t *bg_thread = NULL;
    if (graal_attach_thread(isolate, &bg_thread) != 0) {
        NSLog(@"[Bridge] call_start_server: graal_attach_thread failed");
        return;
    }
    clj_start_server(bg_thread);
    NSLog(@"[Bridge] call_start_server: clj_start_server returned");
    graal_detach_thread(bg_thread);
}

void testme(void) {}

// =============================================================================
// Generic FFI callback — required by tech.v3.datatype.ffi / clj-libffi callback
// machinery (e.g. dispatch-main-async with ObjC blocks).
// bb.o references these as undefined symbols; defined here to satisfy the linker.
// Adapted from TestSkia/TestSkia/MembraneView.mm.
// =============================================================================

typedef void (*operation_t)(void*, void*, void*, void*);

void clj_generic_callback(void *cif, void *ret, void *args, void *userdata) {
    if (!isolate) return;
    long long int key = *((long long int *)userdata);
    graal_isolatethread_t *cb_thread = graal_get_current_thread(isolate);
    if (cb_thread) {
        // Already attached on this OS thread — reuse it.
        com_phronemophobic_clj_libffi_callback(cb_thread, key, ret, args);
    } else {
        // Not yet attached — attach, dispatch, detach.
        graal_attach_thread(isolate, &cb_thread);
        com_phronemophobic_clj_libffi_callback(cb_thread, key, ret, args);
        graal_detach_thread(cb_thread);
    }
}

operation_t clj_get_generic_callback_address(void) {
    return &clj_generic_callback;
}

// =============================================================================
// JDK 21 MacOSXSocketOptions stubs (lowercase k).
//
// JDK 21 renamed getTcpKeepAliveProbes0 → getTcpkeepAliveProbes0 (lowercase k).
// libjava.a (built from JDK 17 source) provides the capital-K implementations.
// The 146 MB bb.o (compiled against JDK 21 GraalVM) generates calls with
// lowercase k, so we forward to the existing capital-K functions.
// =============================================================================

extern int Java_jdk_net_MacOSXSocketOptions_getTcpKeepAliveProbes0(void*, void*, int);
extern void Java_jdk_net_MacOSXSocketOptions_setTcpKeepAliveProbes0(void*, void*, int, int);

int Java_jdk_net_MacOSXSocketOptions_getTcpkeepAliveProbes0(void *env, void *obj, int fd) {
    return Java_jdk_net_MacOSXSocketOptions_getTcpKeepAliveProbes0(env, obj, fd);
}

void Java_jdk_net_MacOSXSocketOptions_setTcpkeepAliveProbes0(void *env, void *obj, int fd, int value) {
    Java_jdk_net_MacOSXSocketOptions_setTcpKeepAliveProbes0(env, obj, fd, value);
}

long long int objc_msgSendU64(const char *s) {
    NSString *ss = @"asdfasdf";
    SEL sel = NSSelectorFromString([NSString stringWithUTF8String:s]);
    return ((NSUInteger (*)(id, SEL))objc_msgSend)(ss, sel);
}

void *objc_make_selector(const char *s) {
    return (void *)NSSelectorFromString([NSString stringWithUTF8String:s]);
}

void *objc_make_string(const char *s) {
    return (__bridge void *)[[NSString alloc] initWithUTF8String:s];
}

void (*fn_ptr)(graal_isolatethread_t *);
void call_clj_fn(void (*clj_fn_ptr)(graal_isolatethread_t *)) {
    fn_ptr = clj_fn_ptr;
    fn_ptr(thread);
}

// =============================================================================
// Block factory shims
//
// Wrap a clj-libffi ffi_closure pointer (plain C function pointer) in an ObjC
// heap block.  The block calling convention on arm64 passes the block itself as
// an implicit first argument to the invoke function; our shim ignores it and
// calls straight through to the ffi_closure, which routes into the Clojure fn.
//
// Ownership: __bridge_retained transfers the +1 retain from ARC to the caller.
// The caller (Clojure side) holds the block pointer in an atom to prevent GC.
// =============================================================================

void *grease_make_void_block(GreaseVoidFn fn) {
    void (^blk)(void) = ^{ fn(); };
    void (^heap)(void) = [blk copy];
    return (__bridge_retained void *)heap;
}

void *grease_make_data_block(GreaseDataFn fn) {
    void (^blk)(NSData *, NSURLResponse *, NSError *) =
        ^(NSData *data, NSURLResponse *response, NSError *error) {
            fn((__bridge void *)data, (__bridge void *)response, (__bridge void *)error);
        };
    void (^heap)(NSData *, NSURLResponse *, NSError *) = [blk copy];
    return (__bridge_retained void *)heap;
}

void *grease_make_bool_error_block(GrallBoolErrFn fn) {
    void (^blk)(BOOL, NSError *) = ^(BOOL success, NSError *error) {
        fn((int)success, (__bridge void *)error);
    };
    void (^heap)(BOOL, NSError *) = [blk copy];
    return (__bridge_retained void *)heap;
}

void grease_call_void_block(void *block) {
    void (^b)(void) = (__bridge void (^)(void))block;
    b();
}

void *grease_make_1ptr_block(Grease1PtrFn fn) {
    void (^blk)(void *) = ^(void *a) { fn(a); };
    void (^heap)(void *) = [blk copy];
    return (__bridge_retained void *)heap;
}

void *grease_make_2ptr_block(Grease2PtrFn fn) {
    void (^blk)(void *, void *) = ^(void *a, void *b) { fn(a, b); };
    void (^heap)(void *, void *) = [blk copy];
    return (__bridge_retained void *)heap;
}

void *grease_make_4ptr_block(Grease4PtrFn fn) {
    void (^blk)(void *, void *, void *, void *) =
        ^(void *a, void *b, void *c, void *d) { fn(a, b, c, d); };
    void (^heap)(void *, void *, void *, void *) = [blk copy];
    return (__bridge_retained void *)heap;
}

// =============================================================================
// Null pointer and main GCD queue shims
// =============================================================================

void *grease_null_ptr(void) {
    return NULL;
}

void *grease_main_queue(void) {
    return (__bridge void *)dispatch_get_main_queue();
}

// =============================================================================
// UIKit frame / geometry shims
//
// CGRect, CGPoint, and CGSize are C structs.  Returning structs through a
// void* FFI call is architecture-specific and clj-libffi does not support
// struct returns.  These scalar wrappers decompose the structs into doubles
// so Clojure can read / write frames without any struct marshaling.
//
// All functions must be invoked on the main thread (UIKit requirement).
// =============================================================================

void grease_set_frame(void *view, double x, double y, double w, double h) {
    UIView *v = (__bridge UIView *)view;
    v.frame = CGRectMake(x, y, w, h);
}

double grease_get_frame_x(void *view) {
    return ((__bridge UIView *)view).frame.origin.x;
}

double grease_get_frame_y(void *view) {
    return ((__bridge UIView *)view).frame.origin.y;
}

double grease_get_frame_w(void *view) {
    return ((__bridge UIView *)view).frame.size.width;
}

double grease_get_frame_h(void *view) {
    return ((__bridge UIView *)view).frame.size.height;
}

void grease_set_center(void *view, double cx, double cy) {
    UIView *v = (__bridge UIView *)view;
    v.center = CGPointMake(cx, cy);
}

double grease_get_center_x(void *view) {
    return ((__bridge UIView *)view).center.x;
}

double grease_get_center_y(void *view) {
    return ((__bridge UIView *)view).center.y;
}

void *grease_class_method_names(void *cls) {
    uint count = 0;
    Method *methods = class_copyMethodList((__bridge Class)cls, &count);
    NSMutableArray *names = [NSMutableArray arrayWithCapacity:count];
    for (uint i = 0; i < count; i++) {
        const char *selName = sel_getName(method_getName(methods[i]));
        if (selName) {
            [names addObject:[NSString stringWithUTF8String:selName]];
        }
    }
    if (methods) free(methods);
    return (__bridge_retained void *)names;
}
