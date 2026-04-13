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

#ifdef __cplusplus
}
#endif

#endif /* Bridge_h */
