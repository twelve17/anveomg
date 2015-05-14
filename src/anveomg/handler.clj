(ns anveomg.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.util.response :refer [redirect]]
            [anveomg.config]
            [anveomg.message.store :as store]
            [anveomg.message.compose :as compose-message]
            [anveomg.message.thread-detail :as thread-detail]
            [anveomg.message.thread-summary :as thread-summary]
            [anveomg.templates :as templates]))

(let [config (anveomg.config/load-config "./etc/config.edn") 
      db (:db config)
      anveo (:anveo config)
      url-prefix (:url-prefix config)
      my-phone-number (:my-number anveo)
      home-url (str url-prefix "/web/messages/thread-summary")]

  (defroutes app-routes
    (GET "/" [] (redirect home-url)) 

    (context "/web" []

             (GET "/messages/thread-summary" {params :params context :context}
                  (let [limit (:limit params) 
                        message-form-url (str context "/message")]
                    (thread-summary/render message-form-url my-phone-number db limit)))

             (GET "/messages/thread/:from/:to" {params :params context :context}
                  (let [message-form-url (str context "/message")] 
                    (thread-detail/render params home-url message-form-url my-phone-number db)))

             (GET "/message" {params :params context :context} 
                  (let [message-form-url (str context "/message")]
                    (compose-message/render params home-url message-form-url my-phone-number)))

             (POST "/message" {params :params context :context} 
                   (let [record (store/send-outgoing-message anveo db params (:mock-send-mode anveo))
                         message-form-url (str context "/message") ]
                     (if (= "success" (:result record))
                       (redirect (thread-detail/url-with-status context record)) 
                       (compose-message/render (select-keys record [:from :to :message])
                                               home-url message-form-url my-phone-number 
                                               (str "Unable to send message: " (:anveo_error record)))))))

    (context "/api" []
             (GET "/message" {params :params}
                  (pr-str (store/save-incoming-message db params))))

    (route/not-found "Not Found")))

(def app (wrap-defaults app-routes site-defaults))
