(ns anveomg.message.compose
  (:require [ring.util.anti-forgery :refer [anti-forgery-field]]
            [net.cgrand.enlive-html :as html]
            [clojure.tools.logging :as log]  
            [anveomg.templates :as templates]))

; [name source selector args & forms]
; "Define a named snippet -- equivalent to (def name (snippet source selector args ...))."
; http://stackoverflow.com/a/26481901/868173
(def snippet (html/snippet "templates/compose.html" 
                           [:div#message-send-content] 
                           [from to message home-url post-url flash] 
                           [:#message-send-content html/any-node] (html/replace-vars { :from from :to to :message message :home-url home-url })
                           [:#message-send-form] (html/do-> 
                                                   (html/append (html/html-snippet (anti-forgery-field)))
                                                   (html/set-attr :action post-url ))
                           [:#flash-message] (if flash
                                               (html/content flash)
                                               (html/set-attr :style "display: none"))))

;(html/defsnippet message-send-form-orig "templates/send.html"
;  [:form]
;  []
;  [:message-list] (html/replace-vars { :from "+0114049649250" })
;  [:form] (html/append (anti-forgery-field))
;  )

;TODO: get rid of 'from' here altogether; always use my-phone-number from config
(defn render
  [params home-url message-form-url my-phone-number & [flash]]
  (let [from (if (clojure.string/blank? (:from params)) my-phone-number (:from params))
        to (if (nil? (:to params)) "" (:to params))
        message (if (nil? (:message params)) "" (:message params))]
    (templates/content-template {:my-phone-number my-phone-number} (snippet from to message home-url message-form-url flash))))

