(ns anveomg.message.thread-summary
  (:require [net.cgrand.enlive-html :as html]
            [anveomg.message.store :as store]
            [anveomg.templates :as templates]
            [anveomg.phone-number :as phone]))

(defn- message-error-draft?
  [record]
  (let [status (:status record)]
    (= "SEND-ERROR-DRAFT" (:status record))))

(defn- message-read? 
  [record]
  (let [status (:status record)]
    (or (= "SENT" status) (= "READ" status))))

(defn- row-attrs  
  ([record extra]
   (let [from (phone/format-db (:from record))
         to (phone/format-db (:to record))]
     (assoc extra :data-message-id (:id record) :data-message-from from :data-message-to to)))
  ([record]
   (row-attrs record {})))

(defn- row-visual-class 
  [record] 
  (if (message-error-draft? record) 
    "unsent-status" 
    (if (message-read? record) 
      "normal-status" 
      "unread-status")))

(defn- record->table-row
  [is-from-me? message-form-url record]
  [:tr (row-attrs record {:class (str "clickable-row " (row-visual-class record))})
   [:td 
    [:div [:span {:class "other-party"} (if is-from-me? (:to record) (:from record))] [:span {:class "received" } (:received record)]]
    [:p {:class "message-text"} (:message record)]]])

; TODO hardcoded colspan
(defn- record->hiccup
  [my-phone-number message-form-url record]
  (let [is-from-me? (phone/equal my-phone-number (:from record))
        parsed-record (store/record->displayable record)]
    (record->table-row is-from-me? message-form-url parsed-record)))

(defn record->html
  [my-phone-number message-form-url record] 
  (html/html (record->hiccup my-phone-number message-form-url record)))

(def snippet (html/snippet "templates/message-thread-summary.html"
                           [:#message-thread-summary]
                           [message-form-url messages]
                           [:#message-thread-summary html/any-node] (html/replace-vars {:message-form-url message-form-url})  
                           [:tbody] (html/content messages)))

(defn render
  [message-form-url my-phone-number db limit]
  (let [record->html (partial record->html my-phone-number message-form-url)
        records-html (store/thread-summary db limit record->html)
        snippet (snippet message-form-url records-html)]
    (templates/content-template {:my-phone-number my-phone-number} snippet)))
