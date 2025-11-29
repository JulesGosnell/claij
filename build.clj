(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.clojars.jules_gosnell/claij)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file "target/claij.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :ns-compile ['claij.server]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'claij.server}))
