//
//  GSL.m
//  grease — GNU Scientific Library wrappers (implementations)
//

#include "GSL.h"
#include <gsl/gsl_sf_gamma.h>
#include <gsl/gsl_sf_erf.h>
#include <gsl/gsl_sf_bessel.h>
#include <gsl/gsl_sf_log.h>
#include <gsl/gsl_sf_exp.h>
#include <gsl/gsl_sf_zeta.h>
#include <gsl/gsl_pow_int.h>

double grease_gsl_sf_gamma(double x)       { return gsl_sf_gamma(x); }
double grease_gsl_sf_erf(double x)         { return gsl_sf_erf(x); }
double grease_gsl_sf_bessel_J0(double x)   { return gsl_sf_bessel_J0(x); }
double grease_gsl_sf_bessel_J1(double x)   { return gsl_sf_bessel_J1(x); }
double grease_gsl_sf_log(double x)         { return gsl_sf_log(x); }
double grease_gsl_sf_exp(double x)         { return gsl_sf_exp(x); }
double grease_gsl_sf_zeta(double s)        { return gsl_sf_zeta(s); }
double grease_gsl_pow_int(double x, int n) { return gsl_pow_int(x, n); }
