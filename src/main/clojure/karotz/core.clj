(ns karotz.core
(:gen-class 
  :name "lt.inventi.karotz.KarotzClojureReporter"
  :implements [lt.inventi.karotz.KarotzReporter])
  (:require [clojure.xml :as xml]))

(import java.util.logging.Level)

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
   (try (let [content (xml/parse (str karotz-api url))  
         code (tag-content :code content)]
     (if (error? code)
       code
       (tag-content :interactiveId content)))
          (catch java.io.IOException e "ERROR"))))

(def tts-pause
 ;karotz cuts about 100ms from begining and 1s from end of media sound.
 ;Thus we have to add extra pause.  
  ". Karotz speeking")

(defn tts-media-url [text]
  (.toString (java.net.URI. "http" "translate.google.lt" "/translate_tts" (str "tl=en&q=" text tts-pause) nil)))

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
  {:api-key <api-key> :install-id <install-id> :secret <secret> :interactive-id <last known interactive id>}"
  ([data]
   (if (valid-id? (:interactive-id data))
     (:interactive-id data)
     (karotz-request (login-url data)))))


(defn user-list [[user & others :as users]]
  (if (empty? others) user
    (str (apply str (interpose ", " (butlast users))) " and " (last users))))

(defn commiters-list [build]
  (user-list (map #(.getId (.getAuthor %)) (.getChangeSet build))))

(defn report-build-state [build-data message]
    (say-out-loud (str (:name build-data) " " message) (sign-in build-data)))

(defn report-failure [build-data build]
  (report-build-state build-data 
                      (str "failed. Last change was made by " (commiters-list build))))

(defn report-recovery [build-data build]
  (report-build-state build-data 
                      (str "is back to normal thanks to " (commiters-list build))))

(defn map-build-data [build descriptor]
  (hash-map :api-key (.getApiKey descriptor) 
            :install-id (.getInstallationId descriptor) 
            :secret (.getSecretKey descriptor)
            :interactive-id (.getInteractiveId descriptor)
            :name (.getName (.getProject build))))

(defn failed? [build]
  (= (.getResult build) hudson.model.Result/FAILURE))

(defn succeed? [build]
  (= (.getResult build) hudson.model.Result/SUCCESS))

(defn recovered? [this-build]
  (let [prev-build (.getPreviousBuild this-build)]
    (if (nil? prev-build)
      false
      (and (succeed? this-build) (failed? prev-build)))))

(def logger (java.util.logging.Logger/getLogger "lt.inventi.karotz.KarotzNotifier"))

(defn -prebuild [this build descriptor]
  (let [build-data (map-build-data build descriptor)]
  (do 
    (.log logger Level/INFO (str "reporting build start " (:name build-data))) 
    (move-ears (sign-in build-data)))))

(defn -perform [this build descriptor]
  (let [build-data (map-build-data build descriptor)]
    (if (failed? build)
      (do 
        (.log logger Level/INFO (str "reporting build failure " (:name build-data))) 
        (report-failure build-data build))
      (if (recovered? build)
        (do 
          (.log logger Level/INFO (str "reporting build recovery " (:name build-data)))
          (report-recovery build-data build))
        (:interactive-id build-data)))))
