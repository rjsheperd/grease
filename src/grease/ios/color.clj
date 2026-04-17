(ns grease.ios.color
  "UIColor coercion from idiomatic Clojure values.

  [[->uicolor]] accepts four input shapes:
  - Keyword  — ~:red~, ~:system-background~, etc. (see resources/grease/colors.edn)
  - Vector   — ~[r g b]~ or ~[r g b a]~, components 0.0–1.0
  - String   — ~\"#FF5733\"~ or ~\"#FF5733AA\"~ hex
  - Pointer  — returned as-is (idempotent)

  Keyword and pointer dispatch require an active ObjC runtime; tag those
  tests ~^:integration~. The hex parser and vector path are pure Clojure."
  (:require [clojure.edn :as edn]
            [com.phronemophobic.grease :as grease]
            [grease.ios-host :as host]
            [grease.ios.objc :as objc-rt]))

;; ── Color name table ────────────────────────────────────────────────────────

(def ^:private color-selectors
  "Map of keyword → UIColor class-method selector string, loaded from EDN."
  (delay
    (edn/read-string (host/read-resource "grease/colors.edn"))))

;; ── Hex string parser ───────────────────────────────────────────────────────

(defn- hex-digit
  "Return the integer value of a single hex character (0–15)."
  [^Character c]
  (let [n (int c)]
    (cond
      (<= (int \0) n (int \9)) (- n (int \0))
      (<= (int \a) n (int \f)) (+ 10 (- n (int \a)))
      (<= (int \A) n (int \F)) (+ 10 (- n (int \A)))
      :else (throw (ex-info (str "Invalid hex digit: " c) {:char c})))))

(defn- hex-pair->double
  "Parse two hex characters starting at `idx` in string `s`, return 0.0–1.0."
  [s idx]
  (/ (double (+ (* 16 (hex-digit (nth s idx))) (hex-digit (nth s (inc idx))))) 255.0))

(defn- parse-hex
  "Parse a CSS-style hex color string into [r g b a] doubles (0.0–1.0).

  Accepts ~\"#RGB\"~, ~\"#RRGGBB\"~, ~\"#RRGGBBAA\"~ (with or without the ~#~ prefix).
  Returns nil if the string is not a recognised hex format."
  [s]
  (let [s (if (and (string? s) (.startsWith ^String s "#")) (subs s 1) s)]
    (when (re-matches #"[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}" s)
      (case (count s)
        3 (let [r (hex-pair->double (str (nth s 0) (nth s 0)) 0)
                g (hex-pair->double (str (nth s 1) (nth s 1)) 0)
                b (hex-pair->double (str (nth s 2) (nth s 2)) 0)]
            [r g b 1.0])
        6 [(hex-pair->double s 0)
           (hex-pair->double s 2)
           (hex-pair->double s 4)
           1.0]
        8 [(hex-pair->double s 0)
           (hex-pair->double s 2)
           (hex-pair->double s 4)
           (hex-pair->double s 6)]))))

;; ── Public API ──────────────────────────────────────────────────────────────

(defn ->uicolor
  "Coerce `spec` to a UIColor pointer.

  | spec shape          | example                    |
  |---------------------+----------------------------|
  | keyword             | ~:red~, ~:system-background~ |
  | ~[r g b]~ vector   | ~[0.2 0.5 0.9]~              |
  | ~[r g b a]~ vector | ~[0.2 0.5 0.9 0.8]~          |
  | hex string          | ~\"#3FBCBC\"~, ~\"#3FBCBCCC\"~  |
  | pointer             | returned as-is             |

  Throws `ex-info` for unrecognised input."
  [spec]
  (cond
    ;; Keyword — class method on UIColor
    (keyword? spec)
    (let [sel (get @color-selectors spec)]
      (when-not sel
        (throw (ex-info (str "Unknown color keyword: " spec)
                        {:spec spec :known (keys @color-selectors)})))
      (objc-rt/msg-send :pointer
                        (grease/get-objc-class "UIColor")
                        sel))

    ;; [r g b] or [r g b a] vector
    (vector? spec)
    (let [[r g b a] spec
          a (or a 1.0)]
      (objc-rt/msg-send :pointer
                        (grease/get-objc-class "UIColor")
                        "colorWithRed:green:blue:alpha:"
                        :float64 (double r)
                        :float64 (double g)
                        :float64 (double b)
                        :float64 (double a)))

    ;; Hex string
    (string? spec)
    (let [rgba (parse-hex spec)]
      (when-not rgba
        (throw (ex-info (str "Unrecognised hex color string: " spec)
                        {:spec spec})))
      (let [[r g b a] rgba]
        (objc-rt/msg-send :pointer
                          (grease/get-objc-class "UIColor")
                          "colorWithRed:green:blue:alpha:"
                          :float64 r :float64 g :float64 b :float64 a)))

    ;; Already a pointer — idempotent
    (grease/convertible-to-pointer? spec) spec

    :else
    (throw (ex-info (str "Cannot coerce to UIColor: " (pr-str spec))
                    {:spec spec}))))
