; http://clojure.github.io/java.jdbc/
(ns anveomg.message.store
  (:require [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [clj-time.local :as l]
            [clj-time.format :as time-format]
            [clj-time.coerce]
            [anveomg.phone-number :as phone]))

(def default-list-limit 50)

; Show human friendly display time, with these shortcut names:
; (today): just show time 
; (yesterday): show "Yesterday <time>"
; (before yesterday): 
;   - if delta days < 7, show week day
;   - if delta days > 7
;      - if years !=, show month and day year + time
;      - else if years ==, show month and day + time
(defn sqltime->humantime
  [sqltime]
  (let [received (l/to-local-date-time (clj-time.coerce/from-long (.getTime sqltime))) 
        now (l/local-now)
        days-between (.getDays (org.joda.time.Days/daysBetween received now))]
    (if (= 0 days-between)
      (str "Today " (.print (org.joda.time.format.DateTimeFormat/forPattern "HH:mm") received))
      (if (= 1 days-between)
        (str "Yesterday " (.print (org.joda.time.format.DateTimeFormat/forPattern "HH:mm") received))
        (if (< days-between 7) 
          (.print (org.joda.time.format.DateTimeFormat/forPattern "EEE HH:mm") received) 
          (let [dt-format (org.joda.time.format.DateTimeFormat/forPattern 
                            (if (= (.getYear received) (.getYear now)) 
                              "MMM d HH:mm"
                              "MMM d yyyy HH:mm"))]
            (.print dt-format received)))))))

(defn- parse-limit 
  [limit default-limit]
  (try 
    (java.lang.Integer/valueOf limit)
    (catch NumberFormatException e default-limit)))

(defn- thread-summary-query 
  []
  "select c1.number as 'from', c2.number as 'to', m1.* 
     from messages m1
       left join contacts c1
         on m1.from_id = c1.id
       left join contacts c2
         on m1.to_id = c2.id
       left join messages m2
         on (
           (m1.from_id = m2.from_id and m1.to_id = m2.to_id) 
         OR
          (m1.from_id = m2.to_id and m1.to_id = m2.from_id) 
         )
         AND m1.received < m2.received
     where m2.from_id is null
     order by received desc
     limit ?")

(defn thread-summary
  ([db limit row-fn] 
   (let [parsed-limit (parse-limit limit default-list-limit)] 
     (j/query db [(thread-summary-query) parsed-limit] :row-fn row-fn)))
  ([db row-fn] 
   (thread-summary db default-list-limit row-fn)))

; AR made me a little SQL rusty :)
; http://stackoverflow.com/a/28090544/868173
; TODO: limit to X recent records
(defn- thread-detail-query 
  []
  "select m.id, c1.number as 'from', c2.number as 'to', m.received, m.status, m.message 
    from messages m, contacts c1, contacts c2 
    where m.from_id = c1.id AND m.to_id = c2.id 
      AND ( 
        (c1.number = ? AND c2.number = ?)
        OR
        (c1.number = ? AND c2.number = ?)
      ) 
    order by received desc
    limit ?")
 
(defn thread-detail
  ([db limit from to row-fn] 
   (let [parsed-limit (parse-limit limit default-list-limit)] 
     (log/info "querying db for thread: " from "<->" to)
     (reverse (j/query db [(thread-detail-query) from to to from parsed-limit] :row-fn row-fn))))
  ([db from to row-fn] 
   (thread-detail db default-list-limit from to row-fn)))

(defn- get-contact-id
  [db number]
  (if (clojure.string/blank? number) 
    (do (log/error "get-contact-id: did not receive number") nil)
    (do 
      (log/info (str "finding contact for number " number))
      (:id (first (j/query db ["select id from contacts where number = ?" number]))))))

(defn- save-contact 
  [db number]
  (try 
    (let [result (j/insert! db :contacts {:number number}) 
          contact_id (:generated_key (first result))]
      (log/info (str "saved contact " number ", id is: " contact_id))
      contact_id)
    (catch Exception e
      (let [contact_id (get-contact-id db number)]   
        (log/error (str "error saving contact: " (.getMessage e) ", assuming already exists--looked up id:" contact_id))
        contact_id)
      )))

(defn delete-thread
  ([db from to] 
   (let [from_id (get-contact-id db from)
         to_id (get-contact-id db to)]
     (log/info "deleting thread: " from "(" from_id ") <-> " to "(" to_id ")")
     (j/delete! db :messages ["(from_id = ? AND to_id = ?) OR (to_id = ? AND from_id = ?)" from_id to_id from_id to_id]))))
 
(defn- timestamp
  []
  (java.sql.Timestamp. (.getTimeInMillis (java.util.Calendar/getInstance))))

;{:smsid "13ba00934bef35b57d9ed9e6140994a0d16aaf74", :fee "-0.01", :parts "1", :error "", :result "success"}
(defn anveo-response->db-map
  [response]
  (let [src (dissoc (clojure.set/rename-keys response {:smsid :sms_id}) :result)]
    (reduce (fn [target [k v]] (assoc target (keyword (str "anveo_" (name  k))) v)) {} src)))

(defn record->displayable
  [record]
  (assoc record 
         :from (phone/format-displayable (:from record))
         :to (phone/format-displayable (:to record))
         :received (sqltime->humantime (:received record))))

(defn record->db-map
  [record]
  (assoc record 
         :from (phone/format-db (:from record))
         :to (phone/format-db (:to record))) )

(defn record->anveo-map
  [record]
  (assoc record 
         :from (phone/format-anveo (:from record))
         :to (phone/format-anveo (:to record))) )

(defn- update-message
  [db id record]
  (log/info "going to update message with id:" id)
  (j/update! db :messages record ["id = ?" id]))

(defn- insert-message
  [db record status]
  (log/info "going to insert new message")
  (let [from_id (save-contact db (:from record))
        to_id (save-contact db (:to record))]
    (log/info (str "insert-message: from-id" from_id ",to-id:" to_id))
    (:generated_key (first  (j/insert! db :messages {
                                                     :from_id from_id 
                                                     :to_id to_id 
                                                     :message (:message record)
                                                     :received (timestamp)
                                                     :status "UNREAD"
                                                     })))))

(defn mark-thread-read 
  [db from to]
  ( let [from_id (get-contact-id db from)
         to_id (get-contact-id db to)]   
    (log/info "marking read: from:" from (str "(" from_id ")") "to:" to (str "(" to_id ")"))
    (j/update! db :messages {:status "READ"} ["(from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)" from_id to_id to_id from_id])))

; From: http://www.anveo.com/api.asp?code=apihelp_sms_send_http&api_type=
;
; https://www.anveo.com/api/v1.asp?apikey=YOURAPIKEY&action=sms&from=FROMPHONENUMBER&destination=DESTINATIONPHONENUMBER&message=TEXTOFTHEMESSAGE
; Anveo returns the status of SMS API as a text. 
;The format of the result text message is as following: 
;result=AAAAAAAA^error=BBBBBBBB^parts=N^fee=ZZZ^smsid=YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY
;where 
;AAAAAAAA - is success when SMS message was sent succesfully and error when there was an error while processing SMS. 
;BBBBBBBB - error text. 
;N - total number of SMS parts used to deliver SMS message. In most cases that number will be 1, however since Anveo supports sending Long SMS messages and in such cases the number of parts could be more then 1. 
;ZZZ - the total cost  (in USD) of sending SMS message. 
;YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY - unique SMS messageid. SMSID is used by SMS delivery report notification  (See Async. Event Notifications)
;Send SMS  (success): 
;https://www.anveo.com/api/v1.asp?apikey=23423423423423asdasd3423asd&action=sms&from=12157010000&destination=12157010680&message=this%20is%20test%20message
;Result 
;result=success^error=^parts=1^fee=0.044
(defn post-message-to-anveo
  [conf record]
  (let [form-params {
                     :action "sms"
                     :from (:from record)
                     :destination (:to record)
                     :message (:message record)
                     :apikey (:call-flow-api-key conf)}]
    (log/info "posting message to anveo: " (pr-str form-params))
    (client/post (:post-message-url conf) {:form-params form-params})))

(defn- send-pushover-notification
  [conf record]
  (let [form-params {
                     :token (:api_token conf)
                     :user (:user_key conf)
                     :device (:device conf)
                     :title (str "Message From " (:from record))
                     :message (:message record)
                     :url (str (:server-url conf) "/web/messages/thread-detail/" (phone/format-db (:from record)) "/" (phone/format-db (:to record))) 
                     }]
    (log/info "sending pushover notification" record)
    (client/post (:api_url conf) {:form-params form-params}))  
  )

(defn- parse-anveo-response
  [response]
  (log/info "parsing response from anveo:" (pr-str response))
  (let [parsed (map #(clojure.string/split % #"=") (clojure.string/split response #"\^"))]
    (reduce (fn 
              [target [k v]] 
              (assoc target (keyword k) (if (nil? v) "" v))) 
            {} 
            parsed))) 

(defn save-outgoing-message
  ([db record]
   (insert-message db record "PENDING"))
  ([db message-id record] 
   (update-message db message-id record)))

(defn save-incoming-message
  [pushover-config db record]
  (log/info "saving incoming message:" record)
  (insert-message db (record->db-map record) "UNREAD")
  (send-pushover-notification pushover-config record))
  

(defn mock-post-message-with-response 
  [db record]
  (let [from (:to record)
        to (:from record)
        message (str "This is a reply message to: " (:message record))
        ;TODO workaround for my SQL query bug for messages in the same second
        response-id (future (Thread/sleep 1000) (save-incoming-message db {:from from :to to :message message}))]
    (log/info "mock sent message to" to "and created response id " response-id)
    {:body "result=success^error=^parts=1^fee=0.00^smsid=8b8f3e43289f5b3e2b3e9c53015acdfae95fa55a"}))

(defn send-outgoing-message 
  [anveo-config db record mock-send-mode]
  (log/info "sending outgoing message:" record)
  (let [message-id (save-outgoing-message db (record->db-map record))
        post-result (if mock-send-mode (mock-post-message-with-response db record) (post-message-to-anveo anveo-config (record->anveo-map record)))
        anveo-response (parse-anveo-response (:body post-result))
        updated-parts (anveo-response->db-map anveo-response)
        new-status (if (= "success" (:result anveo-response)) "SENT" "SEND-ERROR-DRAFT")]
    (save-outgoing-message db message-id (assoc updated-parts :status new-status))
    (merge record anveo-response)))

