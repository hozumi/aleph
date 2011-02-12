;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.http.server
  (:use
    [aleph netty formats]
    [aleph.http utils core websocket]
    [lamina.core]
    [clojure.pprint])
  (:require
    [clojure.contrib.logging :as log])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpHeaders$Names
     HttpChunk
     DefaultHttpChunk
     DefaultHttpResponse
     HttpVersion
     HttpResponseStatus
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.channel
     Channel
     Channels
     ChannelPipeline
     ChannelFuture
     MessageEvent
     ExceptionEvent
     Channels
     DefaultFileRegion]
    [org.jboss.netty.buffer
     ChannelBuffer
     ChannelBufferInputStream
     ChannelBuffers]
    [org.jboss.netty.handler.ssl
     SslHandler]
    [java.io
     ByteArrayInputStream
     InputStream
     File
     FileInputStream
     RandomAccessFile]
    [java.net
     URLConnection]
    [java.security
     KeyStore
     Security
     SecureRandom]
    [javax.net.ssl
     KeyManagerFactory
     SSLContext
     TrustManagerFactory]))

;;;

(defn- respond-with-string
  ([^Channel netty-channel options response]
     (respond-with-string netty-channel options response "utf-8"))
  ([^Channel netty-channel options response charset]
     (let [body (-> response :body (string->byte-buffer charset) byte-buffer->channel-buffer)
	   response (transform-aleph-response
		      (-> response
			(update-in [:headers] assoc "Charset" charset)
			(assoc :body body))
		      options)]
       (write-to-channel netty-channel response false))))

(defn- respond-with-sequence
  ([netty-channel options response]
     (respond-with-sequence netty-channel options response "UTF-8"))
  ([netty-channel options response charset]
     (respond-with-string netty-channel options
       (update-in response [:body] #(apply str %)) charset)))

(defn- respond-with-stream
  [^Channel netty-channel options response]
  (let [stream ^InputStream (:body response)
	response (transform-aleph-response
		   (update-in response [:body] #(input-stream->channel-buffer %))
		   options)]
    (run-pipeline
      (write-to-channel netty-channel response false)
      (fn [_] (.close stream)))))

(defn- respond-with-file
  [netty-channel options response]
  (let [file ^File (:body response)
	content-type (or
		       (URLConnection/guessContentTypeFromName (.getName file))
		       "application/octet-stream")
	fc (.getChannel (RandomAccessFile. file "r"))
	response (-> response
		   (update-in [:headers "Content-Type"] #(or % content-type))
		   (assoc :body fc))]
    (write-to-channel netty-channel
      (transform-aleph-response response options)
      false
      :on-write #(.close fc))))

(defn- respond-with-channel
  [netty-channel options response]
  (let [charset (get-in response [:headers "Charset"] "utf-8")
	response (-> response
		   (assoc-in [:headers "Charset"] charset))
	initial-response ^HttpResponse (transform-aleph-response response options)
	ch (:body response)]
    (run-pipeline (write-to-channel netty-channel initial-response false)
      (fn [_]
	(receive-in-order ch
	  (fn [msg]
	    (when msg
	      (let [msg (transform-aleph-body msg (:headers response) options)
		    chunk (DefaultHttpChunk. msg)]
		(write-to-channel netty-channel chunk false))))))
      (fn [_]
	(write-to-channel netty-channel HttpChunk/LAST_CHUNK false)))))

(defn respond-with-channel-buffer
  [netty-channel options response]
  (let [response (update-in response [:headers "Content-Type"]
		   #(or % "application/octet-stream"))]
    (write-to-channel netty-channel
      (transform-aleph-response response options)
      false)))

(defn respond [^Channel netty-channel options response]
  (let [response (update-in response [:headers]
		   #(merge
		      {"Server" "aleph (0.1.5)"}
		      %))
	body (:body response)]
    (cond
      (nil? body) (respond-with-string netty-channel options (assoc response :body ""))
      (string? body) (respond-with-string netty-channel options response)
      ;;(to-channel-buffer? body) (respond-with-channel-buffer netty-channel options (update-in response [:body] to-channel-buffer))
      (sequential? body) (respond-with-sequence netty-channel options response)
      (channel? body) (respond-with-channel netty-channel options response)
      (instance? InputStream body) (respond-with-stream netty-channel options response)
      (instance? File body) (respond-with-file netty-channel options response)
      :else (throw (Exception. (str "Don't know how to respond with body of type " (class body)))))))

;;;

(defn read-streaming-request
  "Read in all the chunks for a streamed request."
  [headers options in out]
  (run-pipeline in
    read-channel
    (fn [^HttpChunk request]
      (let [last? (.isLast request)
	    body (transform-netty-body (.getContent request) headers options)]
	(if last?
	  (close out)
	  (enqueue out body))
	(when-not last?
	  (restart))))))

(defn handle-request
  "Consumes a single request from 'in', and feed the response into 'out'."
  [^Channel netty-channel options ^HttpRequest netty-request handler in out]
  (let [chunked? (.isChunked netty-request)
	request (assoc (transform-netty-request netty-request options)
		  :scheme :http
		  :remote-addr (channel-origin netty-channel))]
    (if-not chunked?
      (do
	(handler out request)
	nil)
      (let [headers (:headers request)
	    stream (channel)]
	(handler out (assoc request :body stream))
	(read-streaming-request headers options in stream)))))

(defn non-pipelined-loop
  "Wait for the response for each request before processing the next one."
  [^Channel netty-channel options in handler]
  (run-pipeline in
    read-channel
    (fn [^HttpRequest request]
      (let [out (constant-channel)]
	(run-pipeline
	  (handle-request netty-channel options request handler in out)
	  (fn [_] (read-channel out))
	  (fn [response]
	    (respond netty-channel options
	      (pre-process-aleph-message
		(assoc response :keep-alive? (HttpHeaders/isKeepAlive request))
		options)))
	  (constantly request))))
    (fn [^HttpRequest request]
      (if (HttpHeaders/isKeepAlive request)
	(restart)
	(.close netty-channel)))))

(defn simple-request-handler
  [netty-channel options request handler]
  (let [out (constant-channel)]
    (handle-request netty-channel options request handler nil out)
    (receive out
      #(run-pipeline
	 (respond netty-channel options
	   (pre-process-aleph-message
	     (assoc % :keep-alive? false)
	     options))
	 (fn [_] (.close ^Channel netty-channel))))))

(defn http-session-handler [handler options]
  (let [init? (atom false)
	ch (channel)]
    (message-stage
      (fn [netty-channel ^HttpRequest request]
	(if (not (or @init? (.isChunked request) (HttpHeaders/isKeepAlive request)))
	  (simple-request-handler netty-channel options request handler)
	  (do
	    (when (compare-and-set! init? false true)
	      (non-pipelined-loop netty-channel options ch handler))
	    (enqueue ch request)))
	nil))))

(defn load-keystore [keystore key-password]
  (with-open [fis (FileInputStream. keystore)]
    (doto (KeyStore/getInstance (KeyStore/getDefaultType))
      (.load fis (and key-password (.toCharArray key-password))))))

(defn create-ssl-context [{:keys [keystore key-password
				  truststore trust-password]}]
  (let [kmf (KeyManagerFactory/getInstance
	     (or (Security/getProperty "ssl.KeyManagerFactory.algorithm")
		 "SunX509"))
	tmf (when truststore
	      (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm)))
	ssl-context (SSLContext/getInstance "TLS")]
	(.init kmf (load-keystore keystore key-password) (.toCharArray key-password))
	(when tmf
	  (.init tmf (load-keystore truststore trust-password)))
	(.init ssl-context
	       (.getKeyManagers kmf)
	       (when tmf
		    (.getTrustManagers tmf))
	       (SecureRandom.))
	ssl-context))

(defn create-ssl-engine [server-ssl-context]
  (doto (.createSSLEngine server-ssl-context)
    (.setUseClientMode false)))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [pipeline ^ChannelPipeline
	(create-netty-pipeline
	  :decoder (HttpRequestDecoder.)
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :upstream-error (upstream-stage error-stage-handler)
	  :http-request (http-session-handler handler options)
	  :downstream-error (downstream-stage error-stage-handler))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn create-ssl-pipeline
  "Creates an HTTPS pipeline."
  [handler options]
  (let [server-ssl-context (create-ssl-context options)
	pipeline ^ChannelPipeline
	(create-netty-pipeline
	  :ssl (SslHandler. (create-ssl-engine server-ssl-context))
	  :decoder (HttpRequestDecoder.)
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :upstream-error (upstream-stage error-stage-handler)
	  :http-request (http-session-handler handler options)
	  :downstream-error (downstream-stage error-stage-handler))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn start-http-server
  "Starts an HTTP server on the specified :port.  To support WebSockets, set :websocket to
   true.

   'handler' should be a function that takes two parameters, a channel and a request hash.
   The request is a hash that conforms to the Ring standard, with :websocket set to true
   if it is a WebSocket handshake.  If the request is chunked, the :body will also be a
   channel.

   If the request is a standard HTTP request, the channel will accept a single message, which
   is the response.  For a chunked response, the response :body should be a channel.  If the
   request is a WebSocket handshake, the channel represents a full duplex socket, which
   communicates via complete (i.e. non-streaming) strings."
  [handler options]
  (let [https (when (:ssl? options)
		(start-server
		 #(create-ssl-pipeline handler options)
		 (assoc options :port (:ssl-port options))))
	http (start-server
	      #(create-pipeline handler options)
	      options)]
    (fn []
      (when https (https))
      (when http (http)))))




