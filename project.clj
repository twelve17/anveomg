(defproject anveomg "0.1.0-SNAPSHOT"
  :description "AnveOMG - SMS message store and UI for use with anveo.com's SMS gateway"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.3.1"]
                 [ring/ring-defaults "0.1.2"] 
                 [clj-time "0.8.0"]
                 [clj-http "1.1.2"]
                 [com.cemerick/url "0.1.1"]
                 [buddy/buddy-auth "0.6.0"]
                 [com.googlecode.libphonenumber/libphonenumber "7.0.5"]
                 [enlive "1.1.5"]
                 [mysql/mysql-connector-java "5.1.25"] 
                 [org.clojure/java.jdbc "0.3.6"]
                 [org.clojure/tools.logging "0.3.1"]]
  :plugins [[lein-ring "0.8.13"]]
  :ring {:handler anveomg.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring-mock "0.1.5"]]}})
