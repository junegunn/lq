(ns lq.core-test
  (:require [clojure.test :refer :all]
            [lq.core :refer :all])
  (:import (java.io ByteArrayInputStream)))

(defn ->req
  ([method uri]
   {:remote-addr "localhost"
    :request-method method
    :uri uri
    :body (ByteArrayInputStream. (byte-array 0))})
  ([method uri lines]
   {:remote-addr "localhost"
    :request-method method
    :uri uri
    :body (ByteArrayInputStream. (.getBytes (->lines lines)))}))

(defn req
  [& args]
  (-> (apply ->req args) api :body))

(def abc ["a" "b" "c"])

(def cde ["c" "d" "e"])

(deftest api-test
  (testing "Lifecyle"
    (testing "Delete everything"
      (is (re-matches #"[0-9]+\n" (req :delete "/"))))

    (testing "LQ should be empty at this point"
      (is (= "" (req :get "/"))))

    (testing "Create a queue with PUT"
      (is (= "3\n" (req :put "/foo" abc))))

    (testing "Put should be idempotent"
      (is (= "3\n" (req :put "/foo" abc))))

    (testing "foo has 3 lines"
      (is (= "foo 3\n" (req :get "/"))))

    (testing "foo has a, b, and c"
      (is (= (->lines abc) (req :get "/foo"))))

    (testing "bar is empty"
      (is (= "" (req :get "/bar"))))

    (testing "Add lines to bar with POST"
      (is (= "3\n" (req :post "/bar" abc))))

    (testing "bar is not empty"
      (is (= (->lines abc) (req :get "/bar"))))

    (testing "Add more lines to bar with POST. Two lines added excluding the duplicate line."
      (is (= "2\n" (req :post "/bar" cde))))

    (testing "Already there"
      (is (= "0\n" (req :post "/bar" cde))))

    (testing "bar has 5 lines and foo has 3 lines"
      (is (= "bar 5\nfoo 3\n" (req :get "/"))))

    (testing "Shift foo three times"
      (doseq [expected abc]
        (is (= (->line expected) (req :post "/foo/shift")))))

    (testing "foo is now empty"
      (is (= "" (req :get "/foo"))))

    (testing "You can't shift anymore"
      (is (= "" (req :post "/foo/shift"))))

    (testing "bar still has 5 lines and foo is no longer displayed"
      (is (= "bar 5\n" (req :get "/"))))

    (testing "Shift from bar and push to foo"
      (is (= "a\n" (req :post "/bar/to/foo")))
      (is (= "b\n" (req :post "/bar/to/foo"))))

    (testing "bar now has 3 lines and foo got 2"
      (is (= (->lines (take 2 abc)) (req :get "/foo")))
      (is (= (->lines cde) (req :get "/bar")))
      (is (= "bar 3\nfoo 2\n" (req :get "/"))))

    (testing "Containment check"
      (is (= "e\nc\n" (req :get "/bar" ["e" "x" "c"]))))

    (testing "Containment check: no result"
      (is (= "" (req :get "/bar" ["x" "y" "z"]))))

    ;; foo: a b    => a b c e
    ;; bar: c d e  => d
    (testing "Move designated lines"
      (is (= "e\nc\n" (req :post "/bar/to/foo" ["e" "x" "c"]))))

    (testing "Delete topic bar"
      (is (= "1\n" (req :delete "/bar")))
      (is (= "foo 4\n" (req :get "/"))))

    ;; foo: a b c e
    (testing "Delete matching lines"
      (is (= "2\n" (req :delete "/foo" ["b" "e" "x"])))
      (is (= "foo 2\n" (req :get "/")))
      (is (= "2\n" (req :delete "/foo" abc)))
      (is (= "" (req :get "/"))))))
