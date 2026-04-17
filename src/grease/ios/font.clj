(ns grease.ios.font
  "UIFont coercion from idiomatic Clojure values.

  [[->uifont]] accepts four input shapes:

  | spec                     | result                              |
  |--------------------------+-------------------------------------|
  | number                   | system font at that point size      |
  | ~[:system n]~            | system font, size n                 |
  | ~[:system-bold n]~       | bold system font, size n            |
  | ~[:system-italic n]~     | italic system font, size n          |
  | ~[:monospaced n]~        | monospaced digit system font, size n|
  | ~[\"Font Name\" n]~      | named font via ~fontWithName:size:~ |
  | pointer                  | returned as-is (idempotent)         |"
  (:require [com.phronemophobic.grease :as grease]
            [grease.ios.foundation :as f]
            [grease.ios.objc :as objc-rt]))

;; ── Selector table ──────────────────────────────────────────────────────────

(def ^:private font-selectors
  {:system        {:sel "systemFontOfSize:"              :extra-args []}
   :system-bold   {:sel "boldSystemFontOfSize:"          :extra-args []}
   :system-italic {:sel "italicSystemFontOfSize:"        :extra-args []}
   :monospaced    {:sel "monospacedDigitSystemFontOfSize:weight:" :extra-args [:float64 0.0]}})

;; ── Public API ──────────────────────────────────────────────────────────────

(defn ->uifont
  "Coerce `spec` to a UIFont pointer.

  Throws `ex-info` for unrecognised input."
  [spec]
  (cond
    ;; Plain number — system font at that size
    (number? spec)
    (objc-rt/msg-send :pointer
                      (grease/get-objc-class "UIFont")
                      "systemFontOfSize:"
                      :float64 (double spec))

    ;; Vector — [:keyword size] or ["Font Name" size]
    (vector? spec)
    (let [[kw-or-name size] spec]
      (cond
        ;; Named font: ["Helvetica Neue" 14]
        (string? kw-or-name)
        (objc-rt/msg-send :pointer
                          (grease/get-objc-class "UIFont")
                          "fontWithName:size:"
                          :pointer (f/->nsstring kw-or-name)
                          :float64 (double size))

        ;; Keyword font: [:system-bold 16]
        (keyword? kw-or-name)
        (let [{:keys [sel extra-args]} (get font-selectors kw-or-name)]
          (when-not sel
            (throw (ex-info (str "Unknown font keyword: " kw-or-name)
                            {:spec spec :known (keys font-selectors)})))
          (apply objc-rt/msg-send :pointer
                 (grease/get-objc-class "UIFont")
                 sel
                 :float64 (double size)
                 extra-args))

        :else
        (throw (ex-info (str "Cannot coerce to UIFont: " (pr-str spec))
                        {:spec spec}))))

    ;; Already a pointer — idempotent
    (grease/convertible-to-pointer? spec) spec

    :else
    (throw (ex-info (str "Cannot coerce to UIFont: " (pr-str spec))
                    {:spec spec}))))
