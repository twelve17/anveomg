(ns anveomg.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [clojure.tools.logging :as log]  
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.response :refer [redirect]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [net.cgrand.enlive-html :as html]
            [anveomg.config]
            [anveomg.middleware]
            [anveomg.message.store :as store]
            [anveomg.message.compose :as compose-message]
            [anveomg.message.thread-detail :as thread-detail]
            [anveomg.message.thread-summary :as thread-summary]
            [anveomg.templates :as templates]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Controllers                                      ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- login-snippet
  [params]
  (html/snippet "templates/login.html" 
                [:#login-div] 
                [] 
                [:form [:#next]] (html/set-attr :value (:next params))
                [:form] (html/append (html/html-snippet (anti-forgery-field)))))

(defn- login 
  [params]
  ; TODO: hack: using phone # field for "Login" placeholder
  (templates/content-template {:my-phone-number "Login"} ((login-snippet params))))

(defn- logout
  [context]
  (-> (redirect (str context  "/login"))
      (assoc :session {})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authentication                                   ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; https://github.com/funcool/buddy-auth/blob/master/examples/session/src/authexample/web.clj#L58
(defn login-authenticate
  "Check request username and password against users username and passwords.
  On successful authentication, set appropriate user into the session and
  redirect to the value of (:query-params (:next request)).
  On failed authentication, renders the login page."
  [request config]
  (let  [username  (get-in request [:form-params "username"])
         password  (get-in request [:form-params "password"])
         session  (:session request)
         found-password  (get (:users config) (keyword username))]
    (if (and found-password  (= found-password password))
      (let [next-url (str (:public-server-url-base config) (get-in request [:params :next] "/"))
            updated-session (assoc session :identity (keyword username))]
        (log/info "redirecting to: " next-url)
        (-> (redirect next-url)
            (assoc :session updated-session)))
      (login (:params request)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes/Handlers                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(let [config (anveomg.config/load-config "./etc/config.edn") 
      db (:db config)
      anveo (:anveo config)
      pushover (:pushover config)
      url-prefix (:url-prefix config)
      my-phone-number (:my-number anveo)
      home-url (str url-prefix "/web/messages/thread-summary")]

  (defroutes app-routes
    (GET "/" [] (redirect home-url)) 
    (context "/web" []

             (GET "/login" {params :params} (login params))
             (POST "/login" request (login-authenticate request config))
             (GET "/logout" {context :context} (logout context))

             (GET "/messages/thread-summary" request
                  (if-not (authenticated? request)
                    (throw-unauthorized)
                    (let [limit (:limit (:params request)) 
                          message-form-url (str (:context request) "/message")]
                      (thread-summary/render message-form-url my-phone-number db limit))) )

             (GET "/messages/thread/:from/:to" request
                  (if-not (authenticated? request)
                    (throw-unauthorized)

                    (let [message-form-url (str (:context request) "/message")
                          delete-thread-url (:uri request)]
                      (thread-detail/render (:params request) home-url message-form-url delete-thread-url my-phone-number db))))

             (DELETE "/messages/thread/:from/:to" request
                     (if-not (authenticated? request)
                       (throw-unauthorized)
                       (do (store/delete-thread db (:from (:params request)) (:to (:params request)))
                           {:status 204})))

             (GET "/message" request
                  (if-not (authenticated? request)
                    (throw-unauthorized)
                    (let [context (:context request) 
                          params (:params request)
                          message-form-url (str context "/message")]
                      (compose-message/render params home-url message-form-url my-phone-number))))

             (POST "/message" request
                   (if-not (authenticated? request)
                     (throw-unauthorized)
                     (let [context (:context request) 
                           params (:params request)
                           record (store/send-outgoing-message anveo db params (:mock-send-mode anveo))
                           message-form-url (str context "/message") ]
                       (if (= "success" (:result record))
                         (redirect (thread-detail/url-with-status context record)) 
                         (compose-message/render (select-keys record [:from :to :message])
                                                 home-url message-form-url my-phone-number 
                                                 (str "Unable to send message: " (:anveo_error record))))))))

    (context "/api" {server-name :server-name server-port :server-port scheme :scheme}
             (GET "/message" {params :params}
                  (pr-str (store/save-incoming-message (assoc pushover :server-url (:public-server-url-base config)) db params))))

    (route/not-found "Not Found")))

(def app (anveomg.middleware/wrap-defaults app-routes))
