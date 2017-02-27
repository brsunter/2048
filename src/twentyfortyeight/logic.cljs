(ns twentyfortyeight.logic
  (:require [cljs.spec :as s]
            [cljs.spec.impl.gen :as gen]
            [cljs.spec.test :as st]))


(def directions #{::up ::down ::right ::left})

(def board-size 4)

(s/def ::within-board-size (s/int-in 0 board-size))
(s/def ::x ::within-board-size)
(s/def ::y ::within-board-size)
(s/def ::direction directions)
(s/def ::position (s/keys :req [::x ::y]))

(defn log2 [n]
  (* (.log js/Math n) (.-LOG2E js/Math)))

(defn pow-2
  [n]
  (.pow js/Math 2 n))

(defn is-2048-num?
  [n]
  (and (pos-int? n) (mod (log2 n) 1)))

(s/def ::value (s/with-gen is-2048-num?
                 #(gen/fmap pow-2 (s/gen (s/int-in 1 5)))))

(s/def ::tile (s/keys :req [::position ::value]))

(def all-positions (apply concat (for [x (range (dec board-size))]
                                   (for [y (range (dec board-size))]
                                     {::x x ::y y}))))

(defn all-unique-positions?
  [tiles]
  (apply distinct? (map ::position tiles)))

(defn game-board-generator
  []
  (gen/fmap
   (fn [positions] (map (fn [p] {::position p ::value (gen/generate (s/gen ::value))}) positions))
   (gen/set (gen/elements all-positions) {:min-elements 3})))

(s/def ::game-board (s/with-gen
                      (s/and
                       (s/coll-of ::tile :max-count (* board-size board-size))
                       all-unique-positions?)
                      game-board-generator))


(s/fdef move-direction
        :args (s/cat :board ::game-board :direction ::direction)
        :ret ::game-board)

(s/fdef sort-tiles-by-priority
        :args (s/cat :direction ::direction :tiles (s/coll-of ::tile))
        :ret (s/coll-of ::tile))

(defn sort-tiles-by-priority
  [direction tiles]
  (case direction
    ::up (sort-by #(-> % ::position ::y) #(> %1 %2) tiles)
    ::down (sort-by #(-> % ::position ::y) tiles)
    ::left (sort-by #(-> % ::position ::x) #(> %1 %2) tiles)
    ::right (sort-by #(-> % ::position ::x) tiles)))

(s/def ::tiles-to-move (s/map-of ::within-board-size (s/coll-of ::tile)))

(s/fdef group-by-direction
        :args (s/cat :direction ::direction :board ::game-board)
        :ret ::tiles-to-move)

(def vertical?  #{::up ::down})

(def horizontal? #{::left ::right})

(defn group-by-direction
  [direction board]
  (cond
    (vertical? direction) (group-by  #(-> % ::position ::x) board)
    (horizontal? direction) (group-by #(-> % ::position ::y) board)))

(defn maybe-count-decreased-by-one?
  [in-tiles out-tiles]
  (or
   (= (count out-tiles) (count in-tiles))
   (= (count out-tiles) (dec (count in-tiles)))))

(defn indices [pred coll]
  (keep-indexed #(when (pred %2) %1) coll))

(s/fdef join-first
        :args (s/cat :tiles (s/coll-of ::tile :into []))
        :ret (s/coll-of ::tile))
        ;; :fn #(maybe-count-decreased-by-one? (-> % :args :tiles) (-> % :ret)))

(defn join-first
  [tiles]
  (if-let [match  (seq (indices #(<= 2 (count %)) (partition-by ::value tiles)))]
    (let [idx-start  (first match)
          idx-end (+ idx-start 2)
          [first-tile second-tile] (subvec (vec tiles) idx-start idx-end)
          new-value (+ (::value first-tile) (::value second-tile))
          new-position (or (::position second-tile) (::position first-tile))
          new-tile {::value new-value ::position new-position}]
      (concat (take idx-start tiles) [new-tile] (drop idx-end tiles)))
    tiles))

(defn is-stacked-from-top-to-bottom?
  [tiles]
  (let [y-positions (into #{} (map #(-> % ::position ::y)) tiles )
        expected-positions (set (range (count y-positions)))]
    (= y-positions expected-positions)))

(s/fdef stack-top-to-bottom
        :args (s/cat :tiles (s/coll-of ::tile))
        :ret (s/coll-of ::tile)
        :fn #(is-stacked-from-top-to-bottom? (:ret %)))

(defn stack-top-to-bottom
  [tiles]
  (map-indexed (fn [i t] (assoc-in t [::position ::y] i)) (reverse tiles)))

(defn stack-bottom-to-top
  [tiles]
  (map-indexed (fn [i t] (assoc-in t [::position ::y] (- board-size (inc i)))) (reverse tiles)))

(defn stack-left-to-right
  [tiles]
  (map-indexed (fn [i t] (assoc-in t [::position ::x] i)) (reverse tiles)))

(defn stack-right-to-left
  [tiles]
  (map-indexed (fn [i t] (assoc-in t [::position ::x] (- board-size (inc i)))) (reverse tiles)))

(s/fdef stack-tiles
        :args (s/cat :direction ::direction :tiles (s/and (s/coll-of ::tile)))
        :ret (s/coll-of ::tile))

(defn stack-tiles
  [direction tiles]
  (case direction
    ::up (stack-top-to-bottom tiles)
    ::down (stack-bottom-to-top tiles)
    ::right (stack-right-to-left tiles)
    ::left (stack-left-to-right tiles)))

(s/fdef random-open-position
        :args (s/cat :board ::game-board)
        :ret ::position)

(defn random-open-position
  [board]
  (let [occupied-positions (into #{} (map ::position) board)
        free-positions (filter (complement occupied-positions) all-positions)]
    (when (not-empty free-positions) (rand-nth free-positions))))

(defn random-tile-value
  []
  (rand-nth '(2 4)))

(s/fdef insert-new-random-tile
        :args (s/cat :board ::game-board)
        :ret ::game-board)

(defn insert-new-random-tile
  [board]
  (if-let [position (random-open-position board)]
    (conj board {::position position
                 ::value (random-tile-value)})
    board))

(s/fdef move-direction
        :args (s/cat :board ::game-board :direction ::direction)
        :ret ::game-board)

(defn move-direction
  [board direction]
  (->> (group-by-direction direction board)
       (vals)
       (map (partial sort-tiles-by-priority direction))
       (map join-first)
       (mapcat (partial stack-tiles direction))
       (insert-new-random-tile)))

(defn update-state
  [state [event-type & params]]
  (case event-type
    ::move-direction (let [[direction] params] (update state ::game-board #(move-direction % direction)))))

(st/instrument)
