(ns taoensso.carmine.protocol
  "Core facilities for communicating with Redis servers using the Redis
  request/response protocol, Ref. http://redis.io/topics/protocol"
  {:author "Peter Taoussanis"}
  (:require [clojure.string       :as str]
            [taoensso.encore      :as enc]
            [taoensso.nippy.tools :as nippy-tools])
  (:import  [java.io DataInputStream BufferedOutputStream]
            [clojure.lang Keyword]))

;;; Outline (Carmine v3+)
;; * Dynamic context is established with `carmine/wcar`.
;; * Commands executed w/in this context push their requests (vectors) into
;;   context's request queue. Requests may have metadata for Cluster keyslots
;;   and parsers. Parsers may have metadata as a convenient+composable way of
;;   communicating special request requirements (:raw-bulk?, :thaw-opts, etc.).
;; * On `with-reply`, nested `wcar`, or `execute-requests` - queued requests
;;   will actually be sent to server as pipeline.
;; * For non-listener modes, corresponding replies will then immediately be
;;   received, parsed, + returned.

;;;; Dynamic context

(defrecord Context [conn req-queue])
(def ^:dynamic *context* "Current dynamic Context"         nil)
(def ^:dynamic *parser*  "ifn (with optional meta) or nil" nil)

(def no-context-ex
  (ex-info "Redis commands must be called within the context of a connection to Redis server (see `wcar`)" {}))

;;;; Bytes

(defn ^:private -byte-str [^String s] (.getBytes s "UTF-8"))
(def  ^:private ^:const bs-* (int (first (-byte-str "*"))))
(def  ^:private ^:const bs-$ (int (first (-byte-str "$"))))
(def  ^:private ^{:tag 'bytes} bs-crlf   (-byte-str "\r\n"))
(def  ^:private ^{:tag 'bytes} bs-bin "Carmine binary data marker" (-byte-str "\u0000<"))
(def  ^:private ^{:tag 'bytes} bs-clj "Carmine Nippy  data marker" (-byte-str "\u0000>"))

(def ^:private byte-int "Cache common counts, etc."
  (let [cached (enc/memoize_ (fn [n] (-byte-str (Long/toString n))))]
    (fn [n]
      (if (<= ^long n 32767)
        (cached n)
        (-byte-str (Long/toString n))))))

(defn- ensure-reserved-first-byte [^bytes ba]
  (if (and (> (alength ba) 0) (zero? (aget ba 0)))
    (throw (ex-info "Args can't begin with null terminator (byte 0)" {:ba ba}))
    ba))

;;;; Redis<->Clj type coercion
;; TODO Add support for pluggable serialization?

(defrecord WrappedRaw [ba])
(defn raw "Forces byte[] argument to be sent to Redis as raw, unencoded bytes"
  [x]
  (cond
    (enc/bytes?           x) (WrappedRaw. x)
    (instance? WrappedRaw x) x
    :else (throw (ex-info "Raw arg must be byte[]" {:x x :type (type x)}))))

(defprotocol     IByteStr (byte-str [x] "Coerces arbitrary Clojure val to Redis bytestring"))
(extend-protocol IByteStr
  String     (byte-str [x] (ensure-reserved-first-byte (-byte-str               x)))
  Keyword    (byte-str [x] (ensure-reserved-first-byte (-byte-str (enc/as-qname x))))
  Long       (byte-str [x] (byte-int x))
  Integer    (byte-str [x] (byte-int x))
  Short      (byte-str [x] (byte-int x))
  Byte       (byte-str [x] (byte-int x))
  Double     (byte-str [x] (-byte-str (Double/toString x)))
  Float      (byte-str [x] (-byte-str  (Float/toString x)))
  WrappedRaw (byte-str [x] (:ba x))
  nil        (byte-str [x] (enc/ba-concat bs-clj (nippy-tools/freeze x)))
  Object     (byte-str [x] (enc/ba-concat bs-clj (nippy-tools/freeze x))))

(extend-type (Class/forName "[B") IByteStr (byte-str [x] (enc/ba-concat bs-bin x)))

;;;; Basic requests

(defn- send-requests
  "Sends requests to Redis server using its byte string protocol:
    *<no. of args>     crlf
    [$<size of arg N>  crlf
      <arg data>       crlf ...]"
  ;; {:pre [(vector? requests)]}
  [^BufferedOutputStream out requests]
  (enc/run!*
    (fn [req-args]
      (when (pos? (count req-args)) ; [] req is dummy req for `return`
        (let [bs-args (get (meta req-args) :bytestring-req)]
          (.write out bs-*)
          (.write out ^bytes (byte-int (count bs-args)))
          (.write out bs-crlf 0 2)
          (enc/run!*
            (fn [^bytes bs-arg]
              (let [payload-size (alength bs-arg)]
                (.write out bs-$)
                (.write out ^bytes (byte-int payload-size))
                (.write out bs-crlf 0 2)
                (.write out bs-arg  0 payload-size) ; Payload
                (.write out bs-crlf 0 2)))
            bs-args))))
    requests)
  (.flush out))

(defn get-unparsed-reply
  "Implementation detail.
  BLOCKS to receive a single reply from Redis server and returns the result as
  [<type> <reply>]. Redis will reply to commands with different kinds of replies,
  identified by their first byte, Ref. http://redis.io/topics/protocol:

    * `+` for simple strings -> <string>
    * `:` for integers       -> <long>
    * `-` for error strings  -> <ex-info>
    * `$` for bulk strings   -> <clj>/<raw-bytes>    ; Marked as serialized
                             -> <bytes>/<raw-bytes>  ; Marked as binary
                             -> <string>/<raw-bytes> ; Unmarked
                             -> nil
    * `*` for arrays         -> <vector>
                             -> nil"
  [^DataInputStream in req-opts]
  (let [reply-type (int (.readByte in))]
    (enc/case-eval reply-type
      (int \+)                 (.readLine in)
      (int \:) (Long/parseLong (.readLine in))
      (int \-)
      (let [err-str    (.readLine in)
            err-prefix (re-find #"^\S+" err-str) ; "ERR", "WRONGTYPE", etc.
            err-prefix (when err-prefix (keyword (str/lower-case err-prefix)))]
        (ex-info err-str (if-not err-prefix {} {:prefix err-prefix})))

      (int \*)
      (let [bulk-count (Integer/parseInt (.readLine in))]
        (if (== bulk-count -1)
          nil ; Nb was [] with < Carmine v3
          (enc/repeatedly-into [] bulk-count
            (fn [] (get-unparsed-reply in req-opts)))))

      (int \$) ; Bulk strings need checking for special in-data markers
      (let [data-size (Integer/parseInt (.readLine in))]
        (if (== data-size -1)
          nil
          (let [data
                (let [data (byte-array data-size)]
                  (.readFully in data 0 data-size)
                  (.readFully in (byte-array 2) 0 2) ; Discard final crlf
                  ;; Avoid the temptation to use .skipBytes here,
                  ;; Ref. http://stackoverflow.com/a/51393/1982742
                  data)

                data-type
                (cond
                  (get req-opts :raw-bulk?) :raw
                  (< data-size 3)           :str ; No space for marker
                  (zero?   (aget data 0)) ; Special b0 => marker
                  (let [b1 (aget data 1)]
                    (enc/cond!
                      (== b1 62 #_(aget bs-clj 1)) :clj
                      (== b1 60 #_(aget bs-bin 1)) :bin))
                  :else :str)

                payload
                (if (or (identical? data-type :clj)
                        (identical? data-type :bin))
                  (java.util.Arrays/copyOfRange data 2 data-size)
                  data)]

            (try
              (case data-type
                :raw payload
                :str (String. payload "UTF-8")
                :clj (if-let [thaw-opts (:thaw-opts req-opts)]
                       (nippy-tools/thaw payload thaw-opts)
                       (nippy-tools/thaw payload))
                :bin
                ;; Workaround #81 (v2.6.0 may have written _serialized_ bins):
                (if (and ; Nippy header
                      (>= (alength payload) 3)
                      (== (aget payload 0) 78)
                      (== (aget payload 1) 80)
                      (== (aget payload 2) 89))
                  (try
                    (nippy-tools/thaw payload)
                    (catch Exception _ payload))
                  payload))

              (catch Exception e
                (let [message (.getMessage e)]
                  (ex-info (str "Bad reply data: " message)
                    {:message message} e)))))))

      (throw
        (ex-info (str "Server returned unknown reply type: " reply-type)
          {:reply-type reply-type})))))

(defn get-parsed-reply "Implementation detail"
  [^DataInputStream in ?parser]
  (let [;; As an implementation detail, parser metadata is used as req-opts:
        ;; {:keys [raw-bulk? thaw-opts dummy-reply :parse-exceptions?]}. We
        ;; could instead choose to split parsers and req metadata but bundling
        ;; the two is efficient + quite convenient in practice. Note that we
        ;; choose to _merge_ parser metadata during parser comp.
        req-opts (meta ?parser)
        unparsed-reply (if-let [e (find req-opts :dummy-reply)] ; May be nil!
                         (val e)
                         (get-unparsed-reply in req-opts))]

    (if-not ?parser
      unparsed-reply ; Common case
      (if (and (instance? Exception unparsed-reply)
               ;; Nb :parse-exceptions? is rare & not normally used by lib
               ;; consumers. Such parsers need to be written to _not_
               ;; interfere with our ability to interpret Cluster error msgs.
               (not (:parse-exceptions? req-opts)))

        unparsed-reply ; Return unparsed
        (try
          (?parser unparsed-reply)
          (catch Exception e
            (let [message (.getMessage e)]
              (ex-info (str "Parser error: " message)
                {:message message} e))))))))

;;;; Parsers

(defmacro parse
  "Wraps body so that replies to any wrapped Redis commands will be parsed with
  `(f reply)`. Replaces any current parser; removes parser when `f` is nil.
  See also `parser-comp`."
  [f & body] `(binding [*parser* ~f] ~@body))

(defn- comp-maybe [f g] (cond (and f g) (comp f g) f f g g :else nil))
(comment ((comp-maybe nil identity) :x))

(defn parser-comp "Composes parsers when f or g are nnil, preserving metadata"
  [f g] (let [m (merge (meta g) (meta f))] (with-meta (comp-maybe f g) m)))

;;; Special parsers used to communicate metadata to request enqueuer:
(defmacro parse-raw             [& body] `(parse (with-meta identity {:raw-bulk? true})       ~@body))
(defmacro parse-nippy [thaw-opts & body] `(parse (with-meta identity {:thaw-opts ~thaw-opts}) ~@body))

(def return
  "Takes values and returns them as part of next reply from Redis server.
  Unlike `echo`, does not actually send any data to Redis."
  (let [return1
        (fn [context value]
          (swap! context
            (fn [[_ q]]
              [nil (conj q (with-meta [] ; Dummy request
                             {:parser (parser-comp *parser* ; Nb keep context's parser
                                        (with-meta identity {:dummy-reply value}))
                              :expected-keyslot nil ; Irrelevant
                              }))])))]
    (fn
      ([value] (return1 (:req-queue *context*) value))
      ([value & more]
       (enc/run!* (partial return1 (:req-queue *context*))
         (cons value more))))))

(def ^:const suppressed-reply-kw :carmine/suppressed-reply)
(defn-       suppressed-reply? [parsed-reply]
  (identical? parsed-reply suppressed-reply-kw))

(defn- return-parsed-reply "Implementation detail"
  [preply] (if (instance? Exception preply) (throw preply) preply))

(defn return-parsed-replies "Implementation detail"
  [preplies as-pipeline?]
  (let [nreplies (count preplies)]
    (if (or (> nreplies 1) as-pipeline?)
      preplies
      (let [;; nb nil fallback for possible suppressed replies:
            pr1 (nth preplies 0 nil)]
        (return-parsed-reply pr1)))))

(defn- pull-requests "Implementation detail" [req-queue]
  (-> (swap! req-queue (fn [[_ q]] [q []])) (nth 0)))

(defn execute-requests
  "Implementation detail.
  Sends given/dynamic requests to given/dynamic Redis server and optionally
  blocks to receive the relevant queued (pipelined) parsed replies."

  ;; For use with standard dynamic bindings
  ([get-replies? as-pipeline?]
     (let [{:keys [conn req-queue]} *context*
           requests (pull-requests req-queue)]
       (execute-requests conn requests get-replies? as-pipeline?)))

  ;; For use with Cluster, etc.
  ([conn requests get-replies? as-pipeline?]
     (let [nreqs (count requests)]
       (when (pos? nreqs)
         (let [{:keys [in out]} conn]
           (when-not (and in out) (throw no-context-ex))
           ;; (println "Sending requests: " requests)
           (send-requests out requests)
           (when get-replies?
             (if (or (> nreqs 1) as-pipeline?)
               (let [parsed-replies
                     (persistent!
                       (reduce
                         (fn [acc req-in]
                           (let [parsed-reply (get-parsed-reply in
                                                (:parser (meta req-in)))]
                             (if (suppressed-reply? parsed-reply)
                               acc
                               (conj! acc parsed-reply))))
                         (transient [])
                         requests))
                     nparsed-replies (count parsed-replies)]
                 (return-parsed-replies parsed-replies as-pipeline?))

               (let [req (nth requests 0)
                     one-reply (get-parsed-reply in (:parser (meta req)))]
                 (return-parsed-reply one-reply)))))))))

;;;;

(def ^:dynamic *nested-stashed-reqs*     nil)
(def ^:dynamic *nested-stash-consumed?_* nil)

(defn -with-replies "Implementation detail"
  [body-fn as-pipeline?]
  (let [{:keys [conn req-queue]} *context*
        ?nested-stashed-reqs     *nested-stashed-reqs*
        newly-stashed-reqs       (pull-requests req-queue)

        stashed-reqs ; We'll pass the stash down until the first stash consumer
        (if-let [nsr ?nested-stashed-reqs]
          (into nsr newly-stashed-reqs)
          newly-stashed-reqs)]

    (if (empty? stashed-reqs) ; Common case
      (let [_        (body-fn)
            new-reqs (pull-requests req-queue)]
        (execute-requests conn new-reqs :get-replies as-pipeline?))

      (let [nested-stash-consumed?_ (atom false)
            stash-size (count stashed-reqs)

            ?throwable ; Binding to support nested `with-replies` in body-fn:
            (binding [*nested-stashed-reqs*     stashed-reqs
                      *nested-stash-consumed?_* nested-stash-consumed?_]
              (try (body-fn) nil (catch Throwable t t)))

            new-reqs    (pull-requests req-queue)
            all-reqs    (if @nested-stash-consumed?_ new-reqs (into stashed-reqs new-reqs))
            all-replies (execute-requests conn all-reqs :get-replies :as-pipeline)
            _           (when-let [nsc?_ *nested-stash-consumed?_*]
                          (reset! nsc?_ true))

            stashed-replies   (subvec all-replies 0 stash-size)
            requested-replies (subvec all-replies   stash-size)]

        ;; Restore any stashed replies to underlying stateful context:
        (parse nil ; We already parsed on stashing
          (enc/run!* return stashed-replies))

        (if ?throwable
          (throw ?throwable)
          (return-parsed-replies requested-replies as-pipeline?))))))

(defmacro with-replies
  "Alpha - subject to change.
  Evaluates body, immediately returning the server's response to any
  contained Redis commands (i.e. before enclosing context ends).

  As an implementation detail, stashes and then `return`s any replies already
  queued with Redis server: i.e. should be compatible with pipelining.

  Note on parsers: if you're writing a Redis command (e.g. a fn that is
  intended to execute w/in an implicit connection context) and you're using
  `with-replies` as an implementation detail (i.e. you're interpreting
  replies internally), you probably want `(parse nil (with-replies ...))` to
  keep external parsers from leaking into your internal logic."
  {:arglists '([:as-pipeline & body] [& body])}
  [& [s1 & sn :as sigs]]
  (let [as-pipeline? (identical? s1 :as-pipeline)
        body         (if as-pipeline? sn sigs)]
    `(-with-replies (fn [] ~@body) ~as-pipeline?)))

(defmacro with-context "Implementation detail"
  [conn & body]
  `(binding [*context* (Context. ~conn (atom [nil []]))
             *parser*  nil]
     ~@body))
