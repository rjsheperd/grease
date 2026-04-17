(ns calc-app.gsl
  "Clojure wrappers around GSL C functions exposed via Bridge.m / GSL.m.
  Each function calls through `ffi/call` using dlsym(RTLD_DEFAULT, name)
  which finds the statically-linked GSL symbols at runtime."
  (:require [com.phronemophobic.clj-libffi :as ffi]))

(defn gamma
  "Gamma(x) — Γ(n) = (n-1)! for positive integers."
  [x]
  (ffi/call "grease_gsl_sf_gamma" :float64 :float64 x))

(defn erf
  "Error function erf(x) — integral of Gaussian, range [-1, 1]."
  [x]
  (ffi/call "grease_gsl_sf_erf" :float64 :float64 x))

(defn bessel-J0
  "Bessel function of the first kind, order 0: J₀(x)."
  [x]
  (ffi/call "grease_gsl_sf_bessel_J0" :float64 :float64 x))

(defn bessel-J1
  "Bessel function of the first kind, order 1: J₁(x)."
  [x]
  (ffi/call "grease_gsl_sf_bessel_J1" :float64 :float64 x))

(defn ln
  "Natural logarithm ln(x) via GSL."
  [x]
  (ffi/call "grease_gsl_sf_log" :float64 :float64 x))

(defn exp-gsl
  "Exponential e^x via GSL."
  [x]
  (ffi/call "grease_gsl_sf_exp" :float64 :float64 x))

(defn pow-int
  "x raised to integer power n (exact, no floating-point exponent)."
  [x n]
  (ffi/call "grease_gsl_pow_int" :float64 :float64 :sint32 x (int n)))

(defn zeta
  "Riemann zeta function ζ(s)."
  [s]
  (ffi/call "grease_gsl_sf_zeta" :float64 :float64 s))
