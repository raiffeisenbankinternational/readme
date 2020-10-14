This is an example readme file.

It has some setup:

```clojure
=> (:require [clojure.string :as str])
```

and an example using `=>`:

```clojure
=> (:require [clojure.string :as str])
=> (str/starts-with? "example" "ex")
true
```

and a series of examples using `user=>`:

```clojure
=> (+ 1 2 3)
;; comments
6
; are
; ignored
=> (* 1 2 3)
6
=> (- 1 2 3)
-4
=> (println "Hello!")
;; prints:
; Hello!
nil
```

and another `=>` example with trailing forms:

```clojure
=> (:require [clojure.string :as str])
=> (str/ends-with? "example" "nope")
(do (println "This should print!")
    false)
```

```clojure
=> (deftest equal-test
       (is (= 3)
           (+ 1 2)))
```