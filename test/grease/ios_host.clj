(ns grease.ios-host
  "JVM stub for grease.ios-host.

  On device, grease.ios-host is a synthetic SCI namespace providing read-resource
  (classpath resource loader). This stub provides the same function for JVM test
  runs so that grease.ios.types, grease.ios.naming, and grease.ios.spec can load."
  (:require [clojure.java.io :as io]))

(defn read-resource
  "Returns the content of the classpath resource at path as a UTF-8 string,
  or nil if the resource does not exist."
  [path]
  (when-let [url (io/resource path)]
    (slurp url)))
