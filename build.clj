(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def lib 'com.phronemophobic/grease)
(def version "0.1")
(def class-dir "target/classes")

;; Resolved lazily so loading this ns doesn't fail when
;; com.phronemophobic/membrane is unavailable on Clojars.
(def membrane-basis
  (delay (b/create-basis {:project "deps.edn"
                          :aliases [:native-image
                                    :membrane]})))

(def basic-basis
  (delay (b/create-basis {:project "deps.edn"
                          :aliases [:native-image]})))

(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s-uber.jar" (name lib)))
(def src-pom "./pom-template.xml")

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-native-image [_]
  (b/compile-clj {:class-dir class-dir
                  :basis @membrane-basis
                  :java-opts ["-Dtech.v3.datatype.graal-native=true"
                              "-Dclojure.compiler.direct-linking=true"
                              "-Dclojure.spec.skip-macros=true"]
                  :ns-compile '[com.phronemophobic.grease.ios]}))

(defn uberjar-native-image [opts]
  #_(clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (compile-native-image opts)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @membrane-basis
           :main 'com.phronemophobic.grease.ios}))

(defn uberjar-basic [opts]
  (b/delete {:path class-dir})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:class-dir class-dir
                  :basis @basic-basis
                  :java-opts ["-Dtech.v3.datatype.graal-native=true"
                              "-Dclojure.compiler.direct-linking=true"
                              "-Dclojure.spec.skip-macros=true"]
                  :ns-compile '[com.phronemophobic.grease]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basic-basis
           :main 'com.phronemophobic.grease}))

(defn fix-reflect-config [f]
  (let [config (with-open [rdr (io/reader f)]
                 (json/read rdr))
        new-config (->> config
                        (remove (fn [{:strs [name]}]
                                  (str/ends-with? name "__init"))))]
    (with-open [w (io/writer f)]
      (json/write new-config w))))

(defn fix-config [_]
  (fix-reflect-config (io/file "native-image" "config" "reflect-config.json")))
