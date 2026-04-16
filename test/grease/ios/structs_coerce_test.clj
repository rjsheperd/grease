(ns grease.ios.structs-coerce-test
  "Tests for grease.ios.structs pack/unpack coercion.

  All tests run on JVM — no iOS hardware required.
  ByteBuffer I/O is platform-independent; native byte order is used
  (matching the arm64 ABI on device)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [grease.ios.structs :as structs]))

;; =============================================================================
;; Fixture — init struct registry
;; =============================================================================

(use-fixtures :once (fn [f] (structs/init!) (f)))

;; =============================================================================
;; struct-for / known-struct?
;; =============================================================================

(deftest ^:parallel struct-for-test
  (testing "struct-for returns spec for known structs"
    (let [cgrect (structs/struct-for "CGRect")]
      (is (= "CGRect" (:name cgrect)))
      (is (= 32 (:size cgrect)))
      (is (= 2 (count (:fields cgrect)))))))

(deftest ^:parallel known-struct-test
  (testing "known-struct? returns true for known structs and false for unknown"
    (is (true?  (structs/known-struct? "CGRect")))
    (is (true?  (structs/known-struct? "CMTime")))
    (is (false? (structs/known-struct? "NSString")))
    (is (false? (structs/known-struct? "UnknownType99")))))

;; =============================================================================
;; CGPoint — flat double struct
;; =============================================================================

(deftest ^:parallel cgpoint-pack-unpack-test
  (testing "CGPoint round-trips through pack and unpack"
    (let [pt  {:x 3.14 :y 2.71}
          buf (structs/pack "CGPoint" pt)
          out (structs/unpack "CGPoint" buf)]
      (is (instance? java.nio.ByteBuffer buf))
      (is (= 16 (.capacity buf)))
      (is (< (Math/abs (- 3.14 (double (:x out)))) 1e-9))
      (is (< (Math/abs (- 2.71 (double (:y out)))) 1e-9)))))

;; =============================================================================
;; CGSize — flat double struct
;; =============================================================================

(deftest ^:parallel cgsize-pack-unpack-test
  (testing "CGSize round-trips through pack and unpack"
    (let [sz  {:width 375.0 :height 44.0}
          buf (structs/pack "CGSize" sz)
          out (structs/unpack "CGSize" buf)]
      (is (= 16 (.capacity buf)))
      (is (< (Math/abs (- 375.0 (double (:width out)))) 1e-9))
      (is (< (Math/abs (- 44.0  (double (:height out)))) 1e-9)))))

;; =============================================================================
;; CGRect — nested struct (origin: CGPoint, size: CGSize)
;; =============================================================================

(deftest ^:parallel cgrect-pack-unpack-test
  (testing "CGRect with nested structs round-trips"
    (let [rect {:origin {:x 0.0 :y 100.0}
                :size   {:width 375.0 :height 44.0}}
          buf  (structs/pack "CGRect" rect)
          out  (structs/unpack "CGRect" buf)]
      (is (= 32 (.capacity buf)))
      (is (< (Math/abs (- 0.0   (double (get-in out [:origin :x])))) 1e-9))
      (is (< (Math/abs (- 100.0 (double (get-in out [:origin :y])))) 1e-9))
      (is (< (Math/abs (- 375.0 (double (get-in out [:size :width])))) 1e-9))
      (is (< (Math/abs (- 44.0  (double (get-in out [:size :height])))) 1e-9)))))

;; =============================================================================
;; CMTime — mixed int/uint fields
;; =============================================================================

(deftest ^:parallel cmtime-pack-unpack-test
  (testing "CMTime with mixed int field types round-trips"
    (let [t   {:value 1234 :timescale 600 :flags 1 :epoch 0}
          buf (structs/pack "CMTime" t)
          out (structs/unpack "CMTime" buf)]
      (is (= 24 (.capacity buf)))
      (is (= 1234 (long (:value out))))
      (is (= 600  (long (:timescale out))))
      (is (= 0    (long (:epoch out)))))))

;; =============================================================================
;; CLLocationCoordinate2D — two doubles
;; =============================================================================

(deftest ^:parallel coordinate-pack-unpack-test
  (testing "CLLocationCoordinate2D round-trips"
    (let [coord {:latitude 37.7749 :longitude -122.4194}
          buf   (structs/pack "CLLocationCoordinate2D" coord)
          out   (structs/unpack "CLLocationCoordinate2D" buf)]
      (is (= 16 (.capacity buf)))
      (is (< (Math/abs (- 37.7749   (double (:latitude  out)))) 1e-9))
      (is (< (Math/abs (- -122.4194 (double (:longitude out)))) 1e-9)))))

;; =============================================================================
;; Unknown struct throws
;; =============================================================================

(deftest unknown-struct-throws-test
  (testing "pack throws for unknown struct type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown struct"
                          (structs/pack "NSNotAStruct" {}))))
  (testing "struct-for returns nil for unknown type"
    (is (nil? (structs/struct-for "NSNotAStruct")))))
