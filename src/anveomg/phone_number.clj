(ns anveomg.phone-number
  (:import [com.google.i18n.phonenumbers PhoneNumberUtil]))

(def util (com.google.i18n.phonenumbers.PhoneNumberUtil/getInstance))
(def default-region "US")

(defn- parse
  ([number region]
   (.parse util number region))
  ([number]
   (parse number default-region))) 

(defn- is-valid?
  [number region]
  ;(let  util (com.google.i18n.phonenumbers.PhoneNumberUtil/getInstance) 
  (let [parsed-number (parse number region)]
    (.isValidNumber util parsed-number)))

(defn- us-number?
  [number region]
  (let [parsed-number (parse number region)]
    (= 1 (.getCountryCode parsed-number))))

(defn- format-number
  ([number region format-type]
   (try
     (let [parsed-number (parse number region)] 
       (.format util parsed-number format-type))
     (catch Exception e number)))
  ([number format-type]
   (format-number number default-region format-type)))

(defn format-db
  ([number]
   (format-db number default-region))
  ([number region]
   (format-number number region com.google.i18n.phonenumbers.PhoneNumberUtil$PhoneNumberFormat/E164)))

; Looks like anveo does NOT like the plus sign: +15551212
(defn format-anveo
 ([number]
   (format-anveo number default-region))
   ([number region]
   (clojure.string/replace (format-db number) (re-pattern "^\\+") "")))

(defn format-displayable
  ([number]
   (format-displayable number default-region))
  ([number region]
   (format-number number region com.google.i18n.phonenumbers.PhoneNumberUtil$PhoneNumberFormat/NATIONAL)))

(defn equal
  [num1 num2]
  (= (format-db num1) (format-db num2)))
 
