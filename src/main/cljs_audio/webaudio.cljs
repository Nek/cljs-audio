(ns cljs-audio.webaudio
  (:require
    [cljs-audio.webaudio-interpreter :refer [eval-updates! schedule]]
    [cljs.core.async :refer [go promise-chan put! <! >! take!]]
    [cljs.core.async.interop :refer-macros [<p!]]
    [cognitect.transit :as t]
    [cljs-audio.workers.core :as main]
    [cljs-audio.updates :refer [patches->commands ->patch-ast]]
    ["worker-timers" :as worker-timers]
    [promesa.core :as p]))

(defn make-audio [{:keys [polyfill stream buffers] :or {polyfill {} buffers {}}}]
  "Creates an instance of cljs-audio.
  Accepts a polyfill such as standardized-audio-context.
  Falls back to js/AudioContext if there is none provided.
  Returns newly created state."
  (let [polyfill (js->clj polyfill)
        clazz (get polyfill "AudioContext" js/AudioContext)
        ctx (new clazz)]
    {:ctx      ctx
     :patch    [{} #{}]
     :env      {}
     :polyfill (get polyfill "AudioWorkletNode" js/AudioWorkletNode)
     :stream   stream
     :workers  (main/create-pool 4 "js/worker.js")
     :buffers  buffers}))

(defn calculate-updates [{:keys [patch workers]} new-patch]
  "Generates updated state of cljs-audio instance
  and executes web audio side effects.
  Returns updated state."
  (let [p (p/deferred)]
    (take! (main/do-with-pool! workers {:handler   :patches->commands,
                                        :arguments {:old-patch patch
                                                    :new-patch new-patch}})
           #(p/resolve! p (:data %)))
    p))

(defn apply-updates [{:keys [ctx env polyfill buffers]} updates]
  (eval-updates! ctx env polyfill buffers updates))

(defn shift-schedule-time [args delta]
  (if (and (> (count args) 1) (not= 0 (second args)))
    (update-in args [1] #(max 0 (+ % delta)))
    args))

(defn shift-update-time
  [delta update]
  (let [[name node-path param command args] update]
    (if (= :schedule name)
      (let [args (shift-schedule-time args delta)]
        [name node-path param command args])
      update)))

(defn shift-time-by [updates update-time-delta]
  (mapv (partial shift-update-time update-time-delta) updates))

(defn add-module [{:keys [ctx]} path]
  "Fetches audio worklet module.
  Returns a Promise."
  (.addModule (.-audioWorklet ctx) path))

(defn fetch-sample [{:keys [ctx]} path]
  "Fetches sample and decode it to audio data.
  Returns a Promise."
  (-> (js/fetch path)
      (p/then #(.arrayBuffer %))
      (p/then #(.decodeAudioData ctx %))))

(defn current-time
  "Returns current audio context time."
  [{:keys [ctx]}] (.-currentTime ctx))

(defn resume [{:keys [ctx]}]
  (.resume ctx))

(defn set-timeout [{:keys [interval start-time audio]} cb]
  "Schedules time out in a worker.
  Takes time interval in seconds and a callback."
  (.setTimeout worker-timers (fn [] (let [timeout-time (current-time audio)
                                          elapsed-time (- timeout-time start-time)]
                                      (cb (- elapsed-time interval))))
               (* 1000 interval)))

(defn schedule! [env path commands]
  (let [param (last path)
        node-path (butlast path)
        full-path (take (* 2 (count node-path)) (interleave (repeat :group) node-path))]
    (doseq [[command & args] commands]
      (schedule [full-path param command args] env))))

(defn schedule-part [ideal-part-interval audio part-patch lag cb]
  (let [start-time (current-time audio)]
    (calculate-updates
      audio
      part-patch
      (fn [updates]
        (let [updates-done-time (current-time audio)
              updates-calc-interval (- updates-done-time start-time)
              next-part-time-offset (+ lag updates-calc-interval)
              ;; also known as time taken by updates calculation
              ;; without lag from the previous schedule-part cycle
              application-start-time (current-time audio)
              shifted-updates (shift-time-by updates next-part-time-offset)
              new-env (apply-updates audio shifted-updates)
              application-end-time (current-time audio)
              application-interval (- application-end-time application-start-time)
              rest-of-part-interval (- ideal-part-interval lag updates-calc-interval application-interval)]
          (set-timeout {:interval   rest-of-part-interval
                        :audio      audio
                        :start-time (current-time audio)}
                       (fn [part-interval-lag]
                         (cb {:audio     (into audio {:patch part-patch
                                                      :env   new-env})
                              :work-time part-interval-lag}))))))))

(defn calc-updates [audio new-patch cb]
  (let [start-time (current-time audio)]
    (calculate-updates
      audio
      new-patch
      (fn [updates]
        (let [updates-done-time (current-time audio)
              updates-calc-interval (- updates-done-time start-time)
              ;; also known as time taken by updates calculation
              ;; without lag from the previous schedule-part cycle
              application-start-time (current-time audio)
              new-env (apply-updates audio updates)
              application-end-time (current-time audio)
              application-interval (- application-end-time application-start-time)]
          (cb {:audio     (into audio {:patch new-patch
                                       :env   new-env})
               :work-time (+ updates-calc-interval application-interval)}))))))

(defn update! [audio new-patch cb]
  (let [start-time (current-time audio)]
    (calculate-updates
      audio
      new-patch
      (fn [updates]
        (let [updates-done-time (current-time audio)
              updates-calc-interval (- updates-done-time start-time)
              ;; also known as time taken by updates calculation
              ;; without lag from the previous schedule-part cycle
              application-start-time (current-time audio)
              new-env (apply-updates audio updates)
              application-end-time (current-time audio)
              application-interval (- application-end-time application-start-time)]
          (cb {:audio     (into audio {:patch new-patch
                                       :env   new-env})
               :work-time (+ updates-calc-interval application-interval)}))))))

;; value port
;; parameter port

;(defn schedule! [{:keys [env ports ctx]} [port-path event-data]]
;  (let [port-targets (get-port-targets port-path)]
;    ;;  param port
;    ;;  val port
;    (if (port? port-target)
;      (get-in-sibling))))
