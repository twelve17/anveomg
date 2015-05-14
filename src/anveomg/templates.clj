(ns anveomg.templates
  (:require [net.cgrand.enlive-html :as html]))

; https://github.com/ifesdjeen/enlive-ring/blob/master/src/com/ifesdjeen/enlive_ring/core.clj
(html/deftemplate content-template "templates/content.html"
  [replacements content]
  [:title html/any-node] (html/replace-vars replacements) 
  [:#content]  (html/append content))

