(ns cljs-audio.webaudio
  (:require
    [cljs-audio.webaudio-interpreter :refer [eval-updates!]]
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
     :workers  (main/create-pool 1 "js/worker.js")
     :buffers  buffers}))

(defn calculate-updates [{:keys [ctx patch env polyfill stream workers buffers]} new-patch]
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
  (go (<p! (.addModule (.-audioWorklet ctx) "js/noise-generator.js"))))

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

(defn set-timeout [interval]
  "Schedules time out in a worker.
  Takes time interval in seconds and a callback."
  (let [p (p/deferred)]
    (.setTimeout worker-timers #(p/resolve! p) (* 1000 interval))
    p))

(defn schedule-part [ideal-part-interval audio part-patch lag]
  (let [start-time (current-time audio)
        part-interval (- ideal-part-interval lag)
        ;; integrate lag from previous scheduling cycle
        ]
    (p/then (calculate-updates audio part-patch)
            (fn [updates]
              (let [updates-calc-interval (- (current-time audio) start-time)
                    next-part-time-offset (- part-interval updates-calc-interval)
                    ;; also known as time taken by updates calculation
                    ;; without lag from the previous schedule-part cycle
                    shifted-updates (shift-time-by updates next-part-time-offset)
                    new-env (apply-updates audio shifted-updates)
                    updates-calc-and-application-interval (- (current-time audio) start-time)
                    rest-of-part-interval (- part-interval updates-calc-and-application-interval)
                    ;; integrates time taken by updates application
                    ]
                (println next-part-time-offset)
                (p/then (set-timeout rest-of-part-interval)
                        (fn [] (let [part-interval-lag
                                     (- (current-time audio)
                                        (+ (- part-interval
                                              (+ updates-calc-and-application-interval
                                                 rest-of-part-interval))))]
                                 {:audio (into audio {:patch part-patch :env new-env})
                                  :lag   part-interval-lag}))))))))