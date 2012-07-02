(ns karotz.core
  (:gen-class :methods [#^{:static true} [reportFailure [String String String String String] String]
                        #^{:static true} [reportRecovery [String String String String String] String]
                        #^{:static true} [reportBuildStart [String String String String String] String]])
  (:require [clojure.xml :as xml]))

(defn tag-content [tag content]
  (first 
    (for [x (xml-seq content) 
          :when (= tag (:tag x))]
      (first (:content x)))))

(def fail-codes #{"ERROR", "NOT_CONNECTED"})

(defn error? [code]
  (contains? fail-codes code))

(def karotz-api "http://api.karotz.com/api/karotz/")

(defn karotz-request
  ([interactive-id url] (karotz-request (str url "&interactiveid=" interactive-id)))
  ([url] 
   (let [content (xml/parse (str karotz-api url)) 
         code (tag-content :code content)]
     (if (error? code)
       code
       (tag-content :interactiveId content)))))


(def tts-pause
 ;karotz cuts about 100ms from begining and 1s from end of media sound.
 ;Thus we have to add extra pause.  
  " ... ")
(defn tts-media-url [text]
  (.toString (java.net.URI. "http" "translate.google.lt" "/translate_tts" (str "tl=ru&q=" tts-pause text tts-pause) nil)))

(defn say-out-loud [text interactive-id]
  (let [media-url (tts-media-url text)]
    (karotz-request interactive-id (str "multimedia?action=play&url=" (java.net.URLEncoder/encode media-url)))))

(defn move-ears [interactive-id]
  (karotz-request interactive-id "ears?left=20&right=-30&relative=true"))

(defn sign-out 
  ([interactive-id]
   (karotz-request interactive-id "interactivemode?action=stop")))

(defn sign-query [query secret]
  (String. (org.apache.commons.codec.binary.Base64/encodeBase64 
             (let [ mac (javax.crypto.Mac/getInstance "HmacSHA1")]
               (do 
                 (.init mac (javax.crypto.spec.SecretKeySpec. (.getBytes secret) "HmacSHA1"))
                 (.doFinal mac (.getBytes query))))) "ASCII"))

(defn login-url [data]
    (let [query (str "apikey=" (data :api-key) 
                     "&installid=" (data :install-id) 
                     "&once=" (str (.nextInt (java.util.Random.) 99999999)) 
                     "&timestamp=" (long (/ (System/currentTimeMillis) 1000)))]
      (str "start?" query "&signature=" (java.net.URLEncoder/encode (sign-query query (data :secret)) "utf8"))))

(defn valid-id? [interactive-id]
  (if (boolean interactive-id)
    (let [response (karotz-request interactive-id "ears?left=10&right=-10&relative=true")]
      (not (error? response)))))

(defn sign-in 
  "sign-ins to karotz with provided data. Data should be provided as map.
  {:api-key <api-key> :install-id <install-id> :secret <secret>}"
  ([data]
   (sign-in data nil))
  ([data last-interactive-id]
   (if (valid-id? last-interactive-id)
     last-interactive-id
     (karotz-request (login-url data)))))

(defn report-build-state [build login-data interactive-id message]
  (let [interactive-id (sign-in login-data interactive-id)]
    (say-out-loud (str build " " message) interactive-id)))

(defn report-failure [build login-data interactive-id]
  (report-build-state build login-data interactive-id "failed. Fix it ASAP!"))

(defn report-recovery [build login-data interactive-id]
  (report-build-state build login-data interactive-id "is back to normal"))

(defn report-build-start [build login-data interactive-id]
  (let [interactive-id (sign-in login-data interactive-id)]
    (move-ears interactive-id)))

(defn -reportFailure [build api-key install-id secret last-interactive-id]
  (let [sign-data (hash-map :api-key api-key :install-id install-id :secret secret)]
    (report-failure build sign-data last-interactive-id)))

(defn -reportRecovery [build api-key install-id secret last-interactive-id]
  (let [sign-data (hash-map :api-key api-key :install-id install-id :secret secret)]
    (report-recovery build sign-data last-interactive-id)))

(defn -reportBuildStart [build api-key install-id secret last-interactive-id]
  (let [sign-data (hash-map :api-key api-key :install-id install-id :secret secret)]
    (report-build-start build sign-data last-interactive-id)))
