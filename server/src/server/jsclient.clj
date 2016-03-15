(ns server.jsclient
  (:require
   [org.httpkit.server :as httpserver]
   [clojure.data.json :as json]
   [server.db :as db]
   [server.edb :as edb]
   [server.repl :as repl]
   [server.exec :as exec]
   [server.compiler :as compiler]
   [server.smil :as smil]
   [clojure.string :as string])) 

(def bag (atom 10))
;; ok, this is a fucked up rewrite right now. take a parameteric
;; term, use it as the return, and strip it off

(defn format-vec [x]
  (str "[" (string/join "," (map (fn [x] (str "\"" x "\"")) x)) "]"))


  
(defn start-query [d query id connection]
  (let [keys (second query)
        prog (compiler/compile-dsl d @bag (concat (rest (rest query)) (list (list 'return (apply list keys)))))]
    ((exec/open d prog (fn [op tuple]
                         (let [msg (format "{\"type\" : \"result\", \"fields\" : %s, \"values\": %s , \"id\": \"%s\"}"
                                           (format-vec keys)
                                           (str "[" (format-vec tuple) "]")
                                           id)]
                           (println "return" msg)
                           (httpserver/send! connection msg))))
     'flush [])))


(defn handle-connection [d channel]
  ;; this seems a little bad..the stack on errors after this seems
  ;; to grow by one frame of org.httpkit.server.LinkingRunnable.run(RingHandler.java:122)
  ;; for every reception. i'm using this interface wrong or its pretty seriously
  ;; damaged
  
  (httpserver/on-receive
   channel
   (fn [data]
     ;; create relation and create specialization?
     (let [input (json/read-str data)
           query (input "query")
           qs (if query (smil/expand (read-string query)) nil)
           t (input "type")]
       (cond
         (and (= t "query")  (= (first qs) 'query)) (start-query d qs (input "id") channel)
         (and (= t "query")  (= (first qs) 'define)) (repl/define d query)
         ;; should some kind of error
         :else
         (println "jason, wth", input))))))


;; @NOTE: This is trivially exploitable and needs to replaced with compojure or something at some point
(defn serve-static [channel uri]
  (let [prefix (str (.getCanonicalPath (java.io.File. ".")) "/../")]
    (httpserver/send! channel
                      {:status 200
                       :headers {"Expires" "0"
                                 "Cache-Control" "no-cache, private, pre-check=0, post-check=0, max-age=0"
                                 "Pragma" "no-cache"
                                 }
                       :body (slurp (str prefix uri))})))

(defn async-handler [db content]
  (fn [ring-request]
    (httpserver/with-channel ring-request channel    ; get the channel
      (if (httpserver/websocket? channel) 
        (handle-connection db channel)
        (condp = (second (string/split (ring-request :uri) #"/"))
          ;;(= (ring-request :uri) "/favicon.ico") (httpserver/send! channel {:status 404})
          "bin" (serve-static channel (ring-request :uri))
          "css" (serve-static channel (ring-request :uri))
          "repl" (serve-static channel "repl.html")
          (httpserver/send! channel {:status 404}))))))


(import '[java.io PushbackReader])
(require '[clojure.java.io :as io])

(defn serve [db address]
  ;; its really more convenient to allow this to be reloaded
  ;;  (let [content
  ;;        (apply str (map (fn [p] (slurp (clojure.java.io/file (.getPath (clojure.java.io/resource p)))))
  ;;                        '("translate.js"
  ;;                          "db.js"
  ;;                          "edb.js"
  ;;                          "svg.js"
  ;;                          "websocket.js")))]
  ;; xxx - wire up address
  (try (httpserver/run-server (async-handler db "<http><body>foo</body><http>") {:port 8081})
         (catch Exception e (println (str "caught exception: " e (.getMessage e))))))