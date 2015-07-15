(ns anveomg.message.thread-detail
  (:require [net.cgrand.enlive-html :as html]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [cemerick.url :refer (url-encode url-decode)]
            [anveomg.phone-number :as phone]
            [anveomg.message.store :as store]
            [anveomg.templates :as templates]
            [anveomg.url :as url]
            [clojure.tools.logging :as log]))

(defn- record->hiccup
  [my-phone-number record]
  (let [timestamp [:p {:class "message-timestamp"}  (store/sqltime->humantime (:received record))]
        td-width {:style "width: 50%"}
        common-p {:data-id (:id record) :data-from (:from record) :data-to (:to record)}
        is-from-me? (phone/equal my-phone-number (:from record))]
    (if is-from-me?
      [:tr [:td] [:td td-width [:p (merge {:class "triangle-border right" :data-id (:id record)} common-p) (:message record)] timestamp ]]
      [:tr [:td td-width [:p (merge {:class "triangle-border left"} common-p) (:message record)] timestamp] [:td]])))

(defn record->html
  [my-phone-number record]
  (html/html (record->hiccup my-phone-number record)))

;result%3Dsuccess%26error%3D%26parts%3D1%26fee%3D-0.01%26smsid%3D8b8f3e43289f5b3e2b3e9c53015acdfae95fa55a%26from%3D19177653437%26to%3D4049649250%26message%3Dtest%26__anti-forgery-token%3DuAmGPA4bt7832II5uldzCh7pBNzX9%252Br0hF1%252F6k8xlqqQ%252FEWIMxrsQn6IDbdEa88Ldo36zKCoVG1OlSIN
(defn- parse-message-status-str
  [status-str]
  (if (nil? status-str)
    nil 
    (let [status (clojure.walk/keywordize-keys (url/query-str->params (url-decode status-str)))
          fee (clojure.string/replace (:fee status) #"^-" "")]
      (str "Message sent to " (:to status) " in " (:parts status) " part(s), at a cost of $" fee))))

(def snippet (html/snippet "templates/message-thread-detail.html"
                           [:#message-thread-content]
                           [my-phone-number other-party message thread home-url post-url flash]

                           [:#message-thread-content html/any-node] (html/replace-vars {
                                                                                        :heading (phone/format-displayable other-party)
                                                                                        :home-url home-url 
                                                                                        :post-url post-url 
                                                                                        :from my-phone-number
                                                                                        :to other-party
                                                                                        :message message })
                           [:tbody] (html/content thread)

                           [:#flash-message] (if flash
                                               (html/do-> 
                                                 (html/set-attr :style "margin-top: 1.5em") 
                                                 (html/content flash))  
                                               (html/set-attr :style "display: none"))

                           [:#inline-reply-form] ( html/do-> 
                                                   (html/append (html/html-snippet (anti-forgery-field)))
                                                   (html/set-attr :action post-url ))))

(defn render
  [params home-url message-form-url my-phone-number db]
  (let [from (:from params)
        to (:to params)
        is-from-me? (phone/equal my-phone-number from)
        other-party (if is-from-me? to from)
        message (if (nil? (:message params)) "" (:message params)) 
        limit (:limit params)
        raw-status (:send-status params)
        parsed-status (if (nil? raw-status) nil (parse-message-status-str raw-status))
        record->html (partial record->html my-phone-number)
        records-html (store/thread-detail db limit (phone/format-db from) (phone/format-db to) record->html)
        snippet (snippet my-phone-number other-party message records-html home-url message-form-url parsed-status)
        page-html (templates/content-template {:my-phone-number my-phone-number} snippet)]
    (log/info "getting thread: " from "<->" to)
    ; only mark thread read if we were able to read the template above w/o issues
    (store/mark-thread-read db (phone/format-db from) (phone/format-db to))
    page-html))

(defn url-with-status
  [prefix sent-message-record]
  (let [from (:from sent-message-record)
        to (:to sent-message-record)
        status (assoc (select-keys sent-message-record [:fee :parts]) :to (phone/format-displayable to))
        compact-status (url-encode (url/params->query-str status))]
    (str prefix "/messages/thread/" from "/" to "?send-status=" compact-status)))

