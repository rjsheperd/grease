//
//  GSL.h
//  grease — GNU Scientific Library wrappers
//
//  Thin scalar wrappers around GSL functions, callable from Clojure SCI
//  via (ffi/call "grease_gsl_sf_gamma" :float64 :float64 x).
//
//  All functions take and return plain C doubles (no structs, no variadic
//  args) so ffi/call can invoke them directly via dlsym(RTLD_DEFAULT, name).
//

#ifndef GSL_h
#define GSL_h

#ifdef __cplusplus
extern "C" {
#endif

// Special functions
double grease_gsl_sf_gamma(double x);      // Gamma(x)
double grease_gsl_sf_erf(double x);        // Error function erf(x)
double grease_gsl_sf_bessel_J0(double x);  // Bessel J_0(x)
double grease_gsl_sf_bessel_J1(double x);  // Bessel J_1(x)
double grease_gsl_sf_log(double x);        // Natural log (via GSL)
double grease_gsl_sf_exp(double x);        // Exponential (via GSL)
double grease_gsl_sf_zeta(double s);       // Riemann zeta(s)

// Power / integer ops
double grease_gsl_pow_int(double x, int n); // x^n (integer exponent)

#ifdef __cplusplus
}
#endif

#endif /* GSL_h */
