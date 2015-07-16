(ns anveomg.middleware
  (:require 
    [buddy.auth.middleware :refer [wrap-authentication  wrap-authorization]]
    [buddy.auth.backends.session :refer [session-backend]]
    [clojure.tools.logging :as log])
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
    [ring.middleware.proxy-headers :only [wrap-forwarded-remote-addr]]
    [ring.util.response :refer [redirect]])
  )

(defn unauthorized-handler
  [request metadata]
  (let [current-url (:uri request)]
    (redirect (format "/web/login?next=%s" current-url))))

;; Create an instance of auth backend.
(def auth-backend
  (session-backend {:unauthorized-handler unauthorized-handler}))
  
(defn- wrap-logging 
  [handler] 
  (fn [request] (log/info "req:" request) (handler request)))

(defn wrap-defaults
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
 

