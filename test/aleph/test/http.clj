;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.http
  (:use [aleph http])
  (:use
    [lamina core connections]
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]]
    [clojure.contrib.seq :only [indexed]])
  (:import
    [java.io
     File
     ByteArrayInputStream
     StringReader
     PushbackReader]))

;;;

(def string-response "String!")
(def seq-response ["sequence: " 1 " two " 3.0])
(def file-response (File. (str (pwd) "/test/starry_night.jpg")))
(def stream-response "Stream!")

(defn string-handler [request]
  {:status 200
   :header {"content-type" "text/html"}
   :body string-response})

(defn seq-handler [request]
  {:status 200
   :header {"content-type" "text/html"}
   :body seq-response})

(defn file-handler [request]
  {:status 200
   :body file-response})

(defn stream-handler [request]
  {:status 200
   :header {"content-type" "text/html"}
   :body (ByteArrayInputStream. (.getBytes stream-response))})

(def latch (promise))

(def route-map
  {"/stream" stream-handler
   "/file" file-handler
   "/seq" seq-handler
   "/string" string-handler
   "/stop" (fn [_]
	     (try
	       (deliver latch true) ;;this can be triggered more than once, sometimes
	       (@server)
	       (catch Exception e
		 )))})

(defn create-basic-handler []
  (let [num (atom -1)]
    (fn [ch request]
      (when-let [handler (route-map (:uri request))]
	(enqueue-and-close ch
	  (handler request))))))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "seq" (apply str seq-response)]
    (repeat 10)
    (apply concat)
    (partition 2)))

;;;

(defn create-streaming-response-handler []
  (fn [ch request]
    (let [body (apply closed-channel (map str "abcdefghi"))]
      (enqueue ch
	{:status 200
	 :headers {"content-type" "text/plain"}
	 :body body}))))

(defn create-streaming-request-handler []
  (fn [ch request]
    (enqueue ch
      {:status 200
       :headers {"content-type" "application/json"}
       :body (:body request)})))

;;;

(defn wait-for-request [client pre-url path]
  (-> (client {:method :get, :url (str pre-url path)})
    (wait-for-result 1000)
    :body))

(defmacro with-server [handler & body]
  `(do
     (let [kill-fn# (start-http-server ~handler {:port 8080, :auto-transform true})]
      (try
	~@body
	(finally
	  (kill-fn#))))))

'(deftest browser-http-response
   (println "waiting for browser test")
   (start-http-server (create-basic-handler) {:port 8080})
   (is @latch))

(deftest single-requests
  (with-server (create-basic-handler)
    (doseq [[index [path result]] (indexed expected-results)]
      (let [client (http-client {:url "http://localhost:8080"})]
	(is (= result (wait-for-request client "http://localhost:8080/" path)))
	(close-connection client)))))

(deftest multiple-requests
  (with-server (create-basic-handler)
    (let [client (http-client {:url "http://localhost:8080"})]
      (doseq [[index [path result]] (indexed expected-results)]
	(is (= result (wait-for-request client "http://localhost:8080/" path))))
      (close-connection client))))

(deftest streaming-response
  (with-server (create-streaming-response-handler)
    (let [result (sync-http-request {:url "http://localhost:8080", :method :get})]
      (is
	(= (map str "abcdefghi")
	   (channel-seq (:body result) -1))))))

'(deftest streaming-request
  (with-server (create-streaming-request-handler)
    (let [ch (apply closed-channel (range 10))]
      (let [result (sync-http-request
		     {:url "http://localhost:8080"
		      :method :post
		      :headers {"content-type" "application/json"}
		      :body ch})]
	(is
	  (= (range 10)
	     (channel-seq (:body result) -1)))))))

(deftest websocket-server
  (with-server (start-http-server (fn [ch _] (siphon ch ch)) {:port 8081, :websocket true})
    (let [result (run-pipeline (websocket-client {:url "http://localhost:8081"})
		   (fn [ch]
		     (enqueue ch "abc")
		     (read-channel ch 1000)))]
      (is (= "abc" (wait-for-result result))))))

(defmacro with-secure-server [handler & body]
  `(do
     (let [kill-fn# (start-http-server ~handler {:ssl? true,
						 :ssl-port 8443,
						 :keystore "test/my.keystore",
						 :key-password "foobar",
						 :port 8080,
						 :auto-transform true})]
      (try
	~@body
	(finally
	  (kill-fn#))))))

(deftest ssl-request
  (with-secure-server (create-basic-handler)
    (doseq [[index [path result]] (indexed expected-results)]
      (let [client (http-client {:url "https://localhost:8443"})]
	(is (= result (wait-for-request client "https://localhost:8443/" path)))
	(close-connection client)))))