(ns seancorfield.readme-test
  (:require [clojure.test :refer :all]
            [seancorfield.readme :as readme]
            [clojure.java.io :as io])
  (:import (java.nio.file Files Path Paths)
           (java.nio.file.attribute FileAttribute)))



(deftest test-lines-to-blocks
  (is (= [{:actual   "(:require [readme :as :readme])"
           :expected ""}]
         (readme/test-lines->blocks ["=> (:require [readme :as :readme])"
                                     ""])))
  (is (= [{:actual   (str "(:require [readme :as :readme]\n"
                          "          [bla :as [ble]])")
           :expected ""}]
         (readme/test-lines->blocks ["=> (:require [readme :as :readme]"
                                     "             [bla :as [ble]])"])))
  (is (= [{:actual   "(+ 3 4)"
           :expected "7"}]
         (readme/test-lines->blocks ["=> (+ 3 4)"
                                     "7"])))
  (is (= [{:actual   "(+ 3 4)"
           :expected "7"}
          {:actual   (str "(+ 3\n"
                          " 4)")
           :expected "7"}]
         (readme/test-lines->blocks ["=> (+ 3 4)"
                                     "7"
                                     "=> (+ 3"
                                     "    4)"
                                     "7"])))
  (is (= [{:actual   "(:require [clojure.string :as str])"
           :expected ""}
          {:actual   (str "(str/ends-with? \"example\" \"nope\")")
           :expected (str "(do (println \"This should print!\")\n"
                          "   false)")}]
         (readme/test-lines->blocks ["=> (:require [clojure.string :as str])"
                                     "=> (str/ends-with? \"example\" \"nope\")"
                                     "(do (println \"This should print!\")"
                                     "   false)"]))))

(deftest test-class-file
  (is (= (str "(ns root.rtest\n"
              "   (:require [readme :as readme])\n"
              "             [something :s else])\n"
              "(require '[clojure.test :refer :all])\n"
              "\n"
              "\n"
              "\n"
              "(deftest test-1 \n"
              "   (is (= 10\n"
              "          (+ 3 7))))")
         (:test-content (readme/test->class
                          {:test-lines ["=> (:require [readme :as readme])"
                                        "             [something :s else]"
                                        "=> (+ 3 7)"
                                        "10"]
                           :test-key   1
                           :root-ns    "root"
                           :test-name  "rtest"})))))

(deftest with-ident-test
  (is (= (str "a\n"
              "   b")
         (readme/with-ident (str "a\n"
                                 "b")
                            "   "))))

(deftest test-class-file-no-require
  (is (= (str "(ns root.rtest)\n"
              "(require '[clojure.test :refer :all])\n"
              "\n"
              "\n"
              "\n"
              "(deftest test-1 \n"
              "   (is (= 5\n"
              "          (-7\n"
              "            2))))\n\n"
              "(deftest test-2 \n"
              "   (is (= {:a\n"
              "           :b}\n"
              "          (+ 3 7))))")
         (:test-content (readme/test->class
                          {:test-lines ["=> (-7"
                                        "     2)"
                                        "5"
                                        "=> (+ 3 7)"
                                        "{:a"
                                        " :b}"]
                           :test-key   1
                           :root-ns    "root"
                           :test-name  "rtest"})))))


(deftest test-creation
  (io/make-parents "target/src/test")
  (let [target-dir (Files/createTempDirectory (Paths/get "target/src" (make-array String 0))
                                              "test"
                                              (make-array FileAttribute 0))]
    (is (= 0
           (readme/-main "test/seancorfield/readme_example.md"
                         (-> target-dir
                             (.getParent)
                             (.toString))
                         (-> target-dir
                             (.getFileName)
                             (.toString))
                         (fn [code]
                           code))))))