(ns spacon.components.form
  (:require [com.stuartsierra.component :as component]
            [spacon.http.intercept :refer [common-interceptors]]
            [spacon.http.response :as response]
            [spacon.http.auth :refer [check-auth]]
            [spacon.models.form :as formmodel]
            [spacon.components.mqtt :as mqttapi]
            [clojure.spec :as s]
            [spacon.components.mqtt :as mqttapi]
            [spacon.components.trigger :as triggerapi]
            [clojure.data.json :as json]
            [spacon.entity.scmessage :as scm])
  (:import java.net.URLDecoder
           (com.boundlessgeo.spatialconnect.schema SCCommand)))

(defn http-get
  ;; todo: get all the forms for all the teams that the calling user belongs to
  [_]
  ;; select the latest version of each form by the form_key
  (response/ok (formmodel/all)))

(defn http-get-form
  [request]
  ;; todo, we should check form_key and team_id in the query
  (let [form-key (get-in request [:path-params :form-key])]
    (response/ok (formmodel/find-latest-version form-key))))

(defn http-create-form
  "Creates a new form."
  [mqtt request]
  (let [body    (get-in request [:json-params])
        team-id (:team_id body)
        form    (assoc  body :team_id team-id)]
    (if (s/valid? :spacon.models.form/spec form)
      (let [new-form (formmodel/add-form-with-fields form)]
        (mqttapi/publish-scmessage mqtt "/config/update" (scm/map->SCMessage {:action (.value SCCommand/CONFIG_ADD_FORM)
                                                                              :payload new-form}))
        (response/ok new-form))
      (response/bad-request (str "failed to create form:" (s/explain-str :spacon.models.form/spec form))))))

(defn delete-form-by-id
  [form]
  (formmodel/delete (:id form)))

(defn http-delete-form-by-key
  "Deletes all forms matching the form-key"
  [mqtt request]
  (let [form-key (URLDecoder/decode (get-in request [:path-params :form-key]))
        forms (formmodel/find-by-form-key form-key)]
    (doall (map delete-form-by-id forms))
    (mqttapi/publish-scmessage mqtt "/config/update"
                               (scm/map->SCMessage {:action (.value SCCommand/CONFIG_REMOVE_FORM)}))
    (response/ok (str "Deleted form " form-key))))

(defn http-submit-form-data
  [request]
  (let [form-id   (Integer/parseInt (get-in request [:path-params :form-id]))
        form-data (get-in request [:json-params])
        device-id (:device-id form-data)] ;; todo: device-id is not sent yet so this will always be nil
    (formmodel/add-form-data form-data form-id device-id)
    (response/ok "data submitted successfully")))

(defn http-get-form-results
  [request]
  (let [form-id (get-in request [:path-params :form-id])]
    (response/ok (formmodel/get-form-data form-id))))

(defn mqtt->form-submit [trigger message]
  (let [p (:payload message)
        form-id (:form-id p)
        form-data (:feature p)
        device-identifier (:client (:metadata form-data))]
    (triggerapi/check-value trigger form-data)
    (formmodel/add-form-data form-data form-id device-identifier)))

(defn- routes [mqtt]
  #{["/api/forms"                :get    (conj common-interceptors `http-get)]
    ["/api/forms"                :post   (conj common-interceptors check-auth (partial http-create-form mqtt)) :route-name :create-form]
    ["/api/forms/:form-key"      :delete (conj common-interceptors check-auth (partial http-delete-form-by-key mqtt)) :route-name :delete-form]
    ["/api/forms/:form-key"      :get    (conj common-interceptors check-auth `http-get-form)]
    ;; todo: need to figure out why forms causes a route conflict
    ["/api/form/:form-id/submit"   :post   (conj common-interceptors check-auth `http-submit-form-data) :constraints {:form-id #"[0-9]+"}]
    ["/api/form/:form-id/results"  :get   (conj common-interceptors check-auth `http-get-form-results) :constraints {:form-id #"[0-9]+"}]})

(defrecord FormComponent [mqtt trigger]
  component/Lifecycle
  (start [this]
    (mqttapi/subscribe mqtt "/store/form" (partial mqtt->form-submit trigger))
    (assoc this :routes (routes mqtt)))
  (stop [this]
    this))

(defn make-form-component []
  (map->FormComponent {}))