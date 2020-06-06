(ns babashka.test-utils
  (:require
   [babashka.main :as main]
   [clojure.string :as str]
   [me.raynes.conch :refer [let-programs] :as sh]
   [sci.core :as sci]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

(defn normalize [s]
  (if main/windows?
    (let [new-s (str/replace s "\r\n" "\n")]
      (when (not= s new-s)
        (.println System/out "Normalizing output for Windows:")
        (.println System/out s)
        (.println System/out new-s))
      new-s)
    s))

(defn bb-jvm [input-or-opts & args]
  (reset! main/cp-state nil)
  (let [os (java.io.StringWriter.)
        es (if-let [err (:err input-or-opts)]
             err (java.io.StringWriter.))
        is (when (string? input-or-opts)
             (java.io.StringReader. input-or-opts))
        bindings-map (cond-> {sci/out os
                              sci/err es}
                       is (assoc sci/in is))]
    (try
      (when (string? input-or-opts) (vars/bindRoot sci/in is))
      (vars/bindRoot sci/out os)
      (vars/bindRoot sci/err es)
      (sci/with-bindings bindings-map
          (let [res (binding [*out* os
                              *err* es]
                      (if (string? input-or-opts)
                        (with-in-str input-or-opts (apply main/main args))
                        (apply main/main args)))]
            (if (zero? res)
              (normalize (str os))
              (throw (ex-info (str es)
                              {:stdout (str os)
                               :stderr (str es)})))))
      (finally
        (when (string? input-or-opts) (vars/bindRoot sci/in *in*))
        (vars/bindRoot sci/out *out*)
        (vars/bindRoot sci/err *err*)))))

(defn bb-native [input & args]
  (let-programs [bb "./bb"]
    (try (normalize
          (if input
            (apply bb (conj (vec args)
                            {:in input}))
            (apply bb args)))
         (catch Exception e
           (let [d (ex-data e)
                 err-msg (or (:stderr (ex-data e)) "")]
             (throw (ex-info err-msg d)))))))

(def bb
  (case (System/getenv "BABASHKA_TEST_ENV")
    "jvm" #'bb-jvm
    "native" #'bb-native
    #'bb-jvm))

(def jvm? (= bb #'bb-jvm))
(def native? (not jvm?))

(if jvm?
  (println "==== Testing JVM version")
  (println "==== Testing native version"))

(defn socket-loop [^java.net.ServerSocket server]
  (with-open [listener server]
    (loop []
      (with-open [socket (.accept listener)]
        (let [input-stream (.getInputStream socket)]
          (print (slurp input-stream))
          (flush)))
      (recur))))

(defn start-server! [port]
  (let [server (java.net.ServerSocket. port)]
    (future (socket-loop server))
    server))

(defn stop-server! [^java.net.ServerSocket server]
  (.close server))
