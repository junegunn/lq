(ns lq.core
  "LQ is a simple HTTP server that manages named queues of lines of text in
  memory. By using plain-text request and response bodies, it aims to aid shell
  scripting scenarios in distributed environments where it's not feasible to
  set up proper development tools across the nodes (e.g. all you have is curl).

  The underlying data structure for each named queue is LinkedHashSet, so the
  lines in each queue are unique, which may or may not be desirable depending
  on your requirement. So in essense, LQ is just a map of unbounded
  LinkedHashSets exposed via HTTP API. No extra effort has been made to make it
  more efficient or robust."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [DELETE GET POST PUT defroutes]]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
            [ring.util.response :as resp])
  (:import (java.util Iterator LinkedHashSet))
  (:gen-class))

(defonce
  ^{:doc "Managed queues indexed by their names. TODO: We currently don't
  remove queues from the index when they become empty. This can
  theoretically be a source of memory leak."}
  queues
  (atom {}))

(defonce
  ^{:doc "Debug log is disabled by default"}
  debug? false)

(defmacro debug
  "Debug macro"
  [& args]
  (letfn [(fmt [arg] (if (vector? arg) arg [arg]))]
    `(when debug?
       (let [tail# ~(last args)]
         (if (coll? tail#)
           (doseq [expr# (if (seq tail#) tail# [""])]
             (log/info ~@(map fmt (butlast args)) expr#))
           (log/info ~@(map fmt args)))))))

(defn ^java.util.Set create-queue
  "Creates a new data structured to be used as a queue. We currently use
  LinkedHashSet as it's insertion-ordered and it allows fast lookups."
  ([]
   (LinkedHashSet.))
  ([^java.util.Collection lines]
   (LinkedHashSet. lines)))

(defn ^java.util.Set get-queue
  "Returns the queue for the given name. When create is set, creates a new
  queue if not found."
  ([topic]
   (get-queue topic false))
  ([topic create]
   (let [got (volatile! nil)]
     (swap! queues
            (fn [queues]
              (if-let [queue (queues topic)]
                (do (vreset! got queue)
                    queues)
                (if create
                  (assoc queues topic (vreset! got (create-queue)))
                  queues))))
     @got)))

(defmacro with-queue
  "Executes the body with the queue synchronously with explicit locking"
  [[name topic & {:keys [create else]}] & body]
  `(if-let [~name (get-queue ~topic ~create)]
     (locking ~name
       ~@body)
     ~else))

(defn body->lines
  "Splits the request body into lines"
  [body]
  (when body
    (remove empty? (str/split (slurp body) #"\n+"))))

(defn ->line
  "Optionally appends a new line character to the string representation of the
  given object."
  [elem]
  (if elem
    (str elem "\n")
    ""))

(defn ^String ->lines
  "Returns the concatenated string of the lines"
  [lines]
  (apply str (map ->line lines)))

(defn index
  "Lists all queues with their counts"
  []
  (for [[key queue] (sort-by key @queues)
        :let  [queue-count (locking queue (count queue))]
        :when (pos? queue-count)]
    (str key " " queue-count)))

(defn replace!
  "Replaces the queue for the given name"
  [topic lines]
  (let [new-queue (create-queue lines)
        new-size  (count new-queue)]
    (swap! queues assoc topic new-queue)
    new-size))

(defn push!
  "Appends lines to the queue with the given name"
  [topic lines]
  (with-queue [queue topic :create true]
    (count (filter #(.add queue %) lines))))

(defn delete!
  "Deletes lines from the queues with the given name"
  [topic lines]
  (with-queue [queue topic :else 0]
    (count (filter #(.remove queue %) lines))))

(defn clear!
  "Empties the queue"
  [topic]
  (with-queue [queue topic :else 0]
    (let [prev-count (count queue)]
      (.clear queue)
      prev-count)))

(defn clear-all!
  "Removes all data"
  []
  (let [deleted (volatile! 0)]
    (swap! queues
           (fn [queues]
             (vreset! deleted
                      (reduce + (map #(locking % (count %))
                                     (vals queues))))
             {}))
    @deleted))

(defn head
  "Returns the first element in the Set"
  [^java.util.Set queue]
  (when-let [^Iterator iterator (some-> queue .iterator)]
    (when (.hasNext iterator)
      (.next iterator))))

(defn shift!
  "Removes the first line from the queue and returns the line. If the queue
  becomes empty, it will be removed from the index."
  [topic]
  (with-queue [queue topic]
    (let [first-line (head queue)]
      (.remove queue first-line)
      first-line)))

(defn move-impl!
  "Removes lines from the first queue according to remove-fn and adds the
  removed lines to the second queue. remove-fn should return the list of
  removed lines."
  [topic1 topic2 remove-fn]
  (when-let [removed (seq (with-queue [queue1 topic1] (remove-fn queue1)))]
    (with-queue [queue2 topic2 :create true]
      (.addAll queue2 removed))
    removed))

(defn move!
  "Removes lines from the first queue and adds them to the second queue"
  [topic1 topic2 lines]
  (move-impl! topic1 topic2
              (fn [^java.util.Set queue]
                (and queue (filter #(.remove queue %) lines)))))

(defn shift-over!
  "Removes the first line from the first queue and adds it to the second"
  [topic1 topic2]
  (move-impl! topic1 topic2
              (fn [^java.util.Set queue]
                (when-let [first-line (head queue)]
                  (when (.remove queue first-line)
                    [first-line])))))

(defroutes api-routes
  (GET "/" []
    (->lines (index)))

  (DELETE "/" {:keys [remote-addr]}
    (debug remote-addr :clear)
    (->line (clear-all!)))

  (GET "/:topic" [topic :as {:keys [body]}]
    (->lines
     (if-let [lines (seq (body->lines body))]
       (with-queue [queue topic]
         (filter #(.contains queue %) lines))
       (with-queue [queue topic]
         (into [] queue)))))

  (POST "/:topic" [topic :as {:keys [remote-addr body]}]
    (let [lines (body->lines body)]
      (debug remote-addr topic :append lines)
      (->line (push! topic lines))))

  (PUT "/:topic" [topic :as {:keys [remote-addr body]}]
    (let [lines (body->lines body)]
      (debug remote-addr topic :recreate lines)
      (->line (replace! topic lines))))

  (DELETE "/:topic" [topic :as {:keys [remote-addr body]}]
    (->line
     (let [lines (body->lines body)]
       (debug remote-addr topic :delete lines)
       (if-let [lines (seq lines)]
         (delete! topic lines)
         (clear! topic)))))

  (POST "/:topic/shift" [topic :as {:keys [remote-addr]}]
    (let [line (->line (shift! topic))]
      (debug remote-addr topic :shift [line])
      line))

  (POST "/:topic1/to/:topic2" [topic1 topic2 :as {:keys [remote-addr body]}]
    (->lines
     (let [lines (body->lines body)]
       (debug remote-addr [topic1 topic2] :to lines)
       (if-let [lines (seq lines)]
         (move! topic1 topic2 lines)
         (shift-over! topic1 topic2)))))

  (route/not-found ""))

(defn wrap-plain-text-response
  "A middleware that consistently sets the content type of the response to
  text/plain"
  [handler]
  (fn [request]
    (resp/content-type
     (handler request)
     "text/plain; charset=UTF-8")))

(def api
  "API handler"
  (-> api-routes
      (wrap-defaults (assoc-in api-defaults [:params :urlencoded] false))
      wrap-plain-text-response))

(defn parse-args
  "Parses command-line arguments and returns port number. May throw
  IllegalArgumentException."
  [args]
  (let [{flags true args false} (group-by #(str/starts-with? % "-") args)]
    {:debug (some? (some #{"-d" "--debug"} flags))
     :port  (if-let [port (last args)]
              (try (Integer/parseInt port)
                   (catch NumberFormatException e
                     (throw (IllegalArgumentException.
                             (str "Invalid port number: " port)))))
              (throw (IllegalArgumentException. "Port number required")))}))

(defn -main
  [& args]
  (try
    (let [{:keys [debug port]} (parse-args args)]
      (when debug (def debug? true))
      (log/info "Start LQ server at port" port)
      (jetty/run-jetty api {:port port}))
    (catch IllegalArgumentException e
      (log/error (.getMessage e))
      (log/info "usage: java -jar lq.jar [-d] PORT")
      (System/exit 1))))
