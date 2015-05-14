(ns anveomg.url
  (:require [cemerick.url :refer (url-encode url-decode)])
  (:import java.net.URI))

(defn any-blank-params?
  [params to-check]
  (some true? (map #(clojure.string/blank? (% params)) to-check)))
 
(defn pairs->params 
  [pairs]
  (into {} (map (fn [pair] 
         (let [[k v] (clojure.string/split pair #"=")]
           [(url-decode k) (url-decode v)])) 
       pairs)))
 
(defn query-str->pairs
  [query-str] 
  (clojure.string/split query-str #"&"))
 
(defn query-str->params 
  [query-str]
  (pairs->params (query-str->pairs query-str)))

(defn params->pairs
  [params]
    (map 
      (fn [[k v]]  
        (clojure.string/join "=" [(url-encode (name k)) (url-encode v)])) 
      params))

(defn params->query-str
  [params]
  (clojure.string/join "&" (params->pairs params))) 

(defn merge-url-with-params
  [url-str new-params]
  (let [src (java.net.URI. url-str)
        orig-params (pairs->params (.getQuery src))
        merged-params (merge orig-params new-params)]
    (str (.resolve src (str (.getPath src) (str "?" (params->query-str merged-params)))))))

(defn url-with-new-params
  [url-str new-params]
  (let [src (java.net.URI. url-str)]
    (str (.resolve src (str (.getPath src) (str "?" (params->query-str new-params)))))))
