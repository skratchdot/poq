(ns poq.main
  (:require
    [cljs.core.async :refer (chan put! close! <! go timeout) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]
    [alandipert.storage-atom :refer [local-storage]]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [shadow.resource :as rc]))

(defonce state (local-storage (r/atom {}) :state))
(defonce audio (atom {}))

(defn add-noise [x]
  (+ (* (- (js/Math.random) 0.5) 0.0001) x))

(defn make-click-track! [context {:keys [track bpm swing]}]
  (tap> {"make-click-track!" [track bpm swing]})
  (let [beat-seconds (/ (/ 60 bpm) 2)
        beats 16
        sample-rate (aget context "sampleRate")
        frames-per-beat (int (* beat-seconds sample-rate))
        frame-count (* beats frames-per-beat)
        swing-frames (-> swing (/ 100) (* frames-per-beat))
        buffer (.createBuffer context 2 frame-count sample-rate)
        source (.createBufferSource context)]
    (tap> {"sample-rate" sample-rate})
    (tap> {"frames-per-beat" frames-per-beat})
    (doseq [b [0]]
      (let [array-buffer (.getChannelData buffer b)]
        (js/console.log (clj->js (range beats)) beat-seconds sample-rate frames-per-beat)
        (doseq [beat (range beats) i (range frames-per-beat)]
          (let [odd-beat (boolean (mod beat 2))])
          ;(js/console.log (< i 882))
          (aset array-buffer
                (+ i (* beat frames-per-beat))
                (if
                  (if (= (mod beat 2) 0)
                    (< i 882)
                    (and (< (- i swing-frames) 882) (> i swing-frames)))
                  (add-noise -1.5)
                  (add-noise 1.5))))
        (js/console.log array-buffer)))
    (aset source "loop" true)
    (aset source "buffer" buffer)
    (.connect source (aget context "destination"))
    (.start source)
    (when track
      (tap> {"Stopping old track." track})
      (.stop track))
    (tap> "Done generating.")
    source))

(defn update-loop! [state ev]
  (when (@state :playing)
    (swap! state #(-> % (assoc :track (make-click-track! (@audio :context) %))))))

(defn play! [state ev]
  (swap! state assoc :playing true)
  (update-loop! state ev))

(defn stop! [state ev]
  (swap! state dissoc :playing)
  (let [track (@state :track)]
    (when track
      (.stop track))))

(defn average [coll]
  (/ (reduce + coll) (count coll)))

(defn tap! [state ev]
  (let [previous-state @state
        updated-state (swap! state
                             (fn [state]
                               (let [taps (state :taps)
                                     bpm (or (state :bpm) 180)
                                     taps (if (seq? taps) taps [])
                                     now (.getTime (js/Date.))
                                     taps (conj (if (seq? taps) taps []) now)
                                     taps (filter #(> % (- now 3000)) taps)
                                     tap-threshold (> (count taps) 3)
                                     tap-diffs (reduce
                                                 (fn [[last-tap accum] tap]
                                                   [tap
                                                    (if (= last-tap tap)
                                                      accum
                                                      (conj accum (- last-tap tap)))])
                                                 [now []]
                                                 taps)
                                     avg-tap-length (average (second tap-diffs))
                                     bpm (if tap-threshold
                                           (int (/ 60000 avg-tap-length))
                                           bpm)]
                                 (assoc state :taps taps :bpm bpm))))]
    (when (not= updated-state previous-state)
      (js/console.log "CHANGED")
      (update-loop! state ev))))

(defn update-val! [state k ev]
  (swap! state
         assoc k (int (-> ev .-target .-value))))

(defn component-main [state]
  (let [bpm (-> (or (@state :bpm) 180) int (min 170) (max 60))
        swing (-> (or (@state :swing) 0) int (min 100) (max 0))
        playing (@state :playing)]
    [:div
     [:div.input-group
      [:label
       [:input
        {:type "range"
         :min 60
         :max 170
         :on-change (partial update-val! state :bpm)
         :on-mouse-up (partial update-loop! state)
         :on-touch-end (partial update-loop! state)
         :value bpm}]
       "tempo"]]
     [:div.input-group
      [:input#bpm
       {:type "number"
        :on-change (partial update-val! state :bpm)
        :on-blur (partial update-loop! state)
        :on-key-up #(if (= (.-keyCode %) 13) (-> % .-target .blur))
        :on-input #(when (not= (aget js/document "activeElement") (.-target %))
                     (update-loop! state nil))
        :value bpm}]]
     (comment [:div.input-group
               [:label
                [:input {:type "range"
                         :min 0
                         :max 100
                         :on-change (partial update-val! state :swing)
                         :on-mouse-up (partial update-loop! state)
                         :on-touch-end (partial update-loop! state)
                         :value swing}]
                "swing"]])
     [:div.input-group
      (if playing
        [:button {:on-click (partial stop! state)} "stop"]
        [:button {:on-click (partial play! state)} "play"])
      [:button {:on-click (partial tap! state)} "tap"]]]))

(defn reload! []
  (rdom/render [component-main state] (js/document.getElementById "app")))

(defn main! []
  (go
    (<! (timeout 1000))
    (swap! state dissoc :playing)
    (let [audio-context (or (aget js/window "AudioContext") (aget js/window "webkitAudioContext"))]
      (swap! audio assoc :context (audio-context.)))
    (reload!)))
