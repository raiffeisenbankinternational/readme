;; copyright (c) 2020 sean corfield, all rights reserved

(ns seancorfield.readme
  "Turn a README file into a test namespace."
  (:require
    [clojure.tools.logging :as log]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :as ct]))

(defn- parse-forms
  "Given a series of Clojure forms, arrange them into pairs of
  `expected` and `actual` for use in tests, followed by an optional
  hash map containing any 'leftover' forms.

  A sequence can begin with `user=>` followed by exactly one `actual`
  form and exactly one `expected` form, or it can begin with any number
  of `actual` forms (that will be grouped with a `do`) followed by `=>`
  and then exactly one `expected` form. Any remaining forms will be
  returned in a map with the key `::do` to be spliced into a `do`."
  [body]
  (loop [pairs [] [prompt actual expected & more :as forms] body]
    (cond (< (count forms) 3)
          (conj pairs {::do forms})

          (= 'user=> prompt)
          (recur (conj pairs [expected actual]) more)

          :else
          (let [actual (take-while #(not= '=> %) forms)
                expected (when-not (= (count forms) (count actual))
                           (drop (inc (count actual)) forms))]
            (if (seq expected)
              (if (= 1 (count actual))
                (recur (conj pairs [(first expected) (first actual)])
                       (rest expected))
                (recur (conj pairs [(first expected) (cons 'do actual)])
                       (rest expected)))
              (conj pairs {::do actual}))))))

(defmacro defreadme
  "Wrapper for deftest that understands readme examples."
  [name & body]
  (let [organized (partition-by vector? (parse-forms body))
        [pairs tails] (if (map? (ffirst organized))
                        [(second organized) (first organized)]
                        organized)
        assertions (map (fn [[e a]] `(ct/is (~'= ~e ~a))) pairs)
        other-forms (map ::do tails)]
    (if (seq assertions)
      (if (seq other-forms)
        `(do (ct/deftest ~name ~@assertions) ~@(first other-forms))
        `(ct/deftest ~name ~@assertions))
      (if (seq other-forms)
        `(do ~@(first other-forms))
        nil))))

(defn readme->tests
  "Given the path to a README, generate a plain old `clojure.test` file
  at the specified path. If the test path exists, it will be overwritten."
  [{:keys [readme-src] :as ctx}]
  (let [in (io/reader readme-src)]
    (loop [[line & lines] (line-seq in)
           copy false
           line-no 1
           test-map {}
           test-nr 0]
      (if line
        (cond
          (str/starts-with? line ";") (recur lines
                                             copy
                                             line-no
                                             test-map
                                             test-nr)
          (str/starts-with? line "```clojure") (recur lines
                                                      true
                                                      line-no
                                                      test-map
                                                      (inc test-nr))
          (and copy (= line "```")) (recur lines
                                           false
                                           (inc line-no)
                                           test-map
                                           test-nr)
          :else (recur lines
                       copy
                       (inc line-no)
                       (if copy
                         (update test-map test-nr #(vec (conj % line)))
                         test-map)
                       test-nr))
        (assoc ctx :test-map
                   test-map)))))

(defn test-lines->blocks
  "Convert test lines into blocks. Each block starts with prompt user=>
  and spans all lines that start with 6 spaces. This part represents
  code that needs to be executed. After that are lines that describe
  expected value. They start with beginning of line and span until
  end of lines or next user=> prompt"
  [test-lines]
  (loop [[line & lines] test-lines
         blocks []
         in-actual false]
    (if line
      (cond (str/starts-with? line "=>") (recur lines
                                                (conj blocks {:actual   (subs line 3)
                                                              :expected ""})
                                                true)
            (and (str/starts-with? line "   ")
                 in-actual) (recur lines
                                   (conj (pop blocks)
                                         (update (peek blocks)
                                                 :actual
                                                 #(str % "\n" (subs line 3))))
                                   true)
            (and (not (str/starts-with? line "   "))
                 in-actual) (recur lines
                                   (conj (pop blocks)
                                         (assoc (peek blocks)
                                           :expected
                                           line))

                                   false)
            (and (not (str/starts-with? line "   "))
                 (not in-actual)) (recur lines
                                         (conj (pop blocks)
                                               (update (peek blocks)
                                                       :expected
                                                       #(str % "\n" line)))
                                         false))
      blocks)))

(defn with-ident
  [in ident]
  (let [[first & others] (str/split-lines in)]
    (str first
         (reduce
           (fn [p v]
             (str p "\n" ident v))
           ""
           others))))

(defn test->class
  [{:keys [test-lines test-key root-ns test-name] :as ctx}]
  (let [test-ns (str root-ns "." test-name)
        test-blocks (test-lines->blocks test-lines)]

    (println test-blocks)
    (if (some #(contains? % :error) test-blocks)
      (do (log/error "Failed to parse test block " test-key)
          (assoc ctx :error "Parsing failed"))
      (let [first-line (get (first test-blocks) :actual "")
            has-require (str/starts-with? first-line "(:require")
            header (if has-require
                     (str "\n" "   " (with-ident first-line "   ")))
            body (if has-require
                   (rest test-blocks)
                   test-blocks)]

        (assoc
          ctx
          :test-ns test-ns
          :test-content (loop [[block & blocks] body
                               block-nr 1
                               out (str "(ns " test-ns header ")\n"
                                        "(require '[clojure.test :refer :all])\n\n")]
                          (if block
                            (recur
                              blocks
                              (inc block-nr)
                              (str out "\n\n"
                                   "(deftest test-" block-nr " \n"
                                   "   (is (= " (with-ident (:expected block) "          ") "\n"
                                   "          " (with-ident (:actual block) "          ") ")))"))
                            out)))))))



(defn class->file
  [{:keys [test-ns
           test-name
           test-dir
           test-content] :as ctx}]
  (let [test-file (str test-dir "/" (str/replace test-name "-" "_") ".clj")]
    (log/info "Targeting test:" test-file)
    (io/make-parents test-file)
    (spit test-file test-content)
    (try
      (require (symbol test-ns) :reload)
      (assoc ctx :test-ns test-ns)
      (catch Throwable t
        (log/error "\nFailed to require" test-ns)
        (log/error (.getMessage t))
        (some->> t
                 (.getCause)
                 (.getMessage)
                 (log/error "Caused by"))
        (assoc ctx :error true)))))

(defn tests->class
  [{:keys [test-map] :as ctx}]
  (reduce
    (fn [c [key value]]
      (log/info "Parsing" (str/join "\n" (cons "" value)))
      (let [{:keys [test-ns error]} (-> (assoc c
                                          :test-lines value
                                          :test-key key
                                          :test-name (str "readme-" key))
                                        (test->class)
                                        (class->file))]
        (log/info "Eval result:" test-ns ";error:" error)
        (if error
          (assoc c :error true)
          (update c :test-namespaces #(conj % test-ns)))))
    (assoc ctx
      :test-namespaces [])
    test-map))


(defn main
  "A useful default test behavior that can be invoked from the command
  line via `-m seancorfield.readme`

  This turns `README.md` (if it exists) into `src/readme.clj`, then
  requires it and runs its tests, and finally deletes `src/readme.clj`.

  Optional arguments for the readme file and the generated test can override
  the defaults."
  [{:keys [readme-src target-dir root-ns exit-fn] :as ctx}]
  (log/info "Scanning" readme-src "in" readme-src "with ns" root-ns)
  (io/make-parents target-dir)
  (when (.exists (io/file readme-src))
    (let [{:keys [error test-namespaces]} (-> ctx
                                              (readme->tests)
                                              (tests->class))]
      (log/info "Resolved namespaces:" test-namespaces)
      (if error
        (do
          (log/error "Resolution error:" error)
          ((:exit-fn ctx) 1))
        (let [summary (try
                        (apply ct/run-tests (mapv
                                              #(symbol %1)
                                              test-namespaces))
                        (catch Throwable t
                          (log/error "Some tests failed" t)))]
          (if (and summary
                   (number? (:fail summary))
                   (number? (:error summary))
                   (zero? (+ (:fail summary) (:error summary))))
            (do
              (-> (str target-dir "/" root-ns)
                  (io/file)
                  (.delete))
              (exit-fn 0))
            (exit-fn 1)))))))

(defn -main
  [& [readme-src target-dir root-ns exit-fn]]
  (let [target-dir-resolved (or target-dir "target/src")
        root-ns-resolved (str/replace (or root-ns "readme-test") "-" "_")
        ctx {:readme-src (or readme-src "README.md")
             :target-dir target-dir-resolved
             :test-dir   (str target-dir-resolved "/" root-ns-resolved)
             :root-ns    root-ns-resolved
             :exit-fn    (or exit-fn (fn [status]
                                       (shutdown-agents)
                                       (System/exit status)))}]
    (main ctx)))
