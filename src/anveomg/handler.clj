(ns anveomg.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [clojure.tools.logging :as log]  
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.response :refer [redirect]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication  wrap-authorization]]
            [net.cgrand.enlive-html :as html]
            [anveomg.config]
            [anveomg.message.store :as store]
            [anveomg.message.compose :as compose-message]
            [anveomg.message.thread-detail :as thread-detail]
            [anveomg.message.thread-summary :as thread-summary]
            [anveomg.templates :as templates])
  (:use 
    [ring.middleware.flash :only [wrap-flash]]
    [ring.middleware.session :only [wrap-session]]
    [ring.middleware.session.cookie :only [cookie-store]]
    [ring.middleware.keyword-params :only [wrap-keyword-params]]
    [ring.middleware.nested-params :only [wrap-nested-params]]
    [ring.middleware.anti-forgery :only [wrap-anti-forgery]]
    [ring.middleware.multipart-params :only [wrap-multipart-params]]
    [ring.middleware.params :only [wrap-params]]
    [ring.middleware.cookies :only [wrap-cookies]]
    [ring.middleware.resource :only [wrap-resource]]
    [ring.middleware.not-modified :only [wrap-not-modified]]
    [ring.middleware.content-type :only [wrap-content-type]]
    [ring.middleware.absolute-redirects :only [wrap-absolute-redirects]]
    [ring.middleware.proxy-headers :only [wrap-forwarded-remote-addr]]))


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

(defn unauthorized-handler
  [request metadata]
  (let [current-url (:uri request)]
    (redirect (format "/web/login?next=%s" current-url))))

;; Create an instance of auth backend.
(def auth-backend
  (session-backend {:unauthorized-handler unauthorized-handler}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes/Handlers                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-logging 
  [handler] 
  (fn [request] (log/info "req:" request) (handler request)))

(defn- anveo-wrap-defaults
  [handler]
  (-> handler
;      (wrap-logging)
      (wrap-authorization auth-backend)
      (wrap-authentication auth-backend)
      (wrap-flash)
      (wrap-anti-forgery)
      (wrap-session)
      (wrap-keyword-params)
      (wrap-nested-params)
      ;(wrap-multipart-params (get-in config [:params :multipart] false))
      (wrap-params)
      (wrap-cookies)
      (wrap-absolute-redirects)
      (wrap-resource "public")
      ;(wrap-file)
      (wrap-content-type)
      ;(wrap-not-modified     (get-in config [:responses :not-modified-responses] false))
      ;(wrap-x-headers        (:security config))
      ;(wrap-hsts)
      ;(wrap-ssl-redirect     (get-in config [:security :ssl-redirect] false))
      ;(wrap-forwarded-scheme)
      (wrap-forwarded-remote-addr)))


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
                  (log/info "request session:" (:session request))
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

(def app (anveo-wrap-defaults app-routes))
