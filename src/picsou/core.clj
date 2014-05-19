(ns picsou.core
  (:use [picsou.helpers]
        [clojure.tools.logging :exclude [trace]]
        [plumbing.core])
  (:require [clojure.string :as strings]
            [picsou.engine :as engine]
            [picsou.time :as time]
            [picsou.learn :as learn]
            [picsou.util :as util]
            [clojure.java.io :as io]
            [picsou.corpus :as corpus]))

(def rules-map (atom {}))
(def corpus-map (atom {}))
(def classifiers-map (atom {}))
(def default-lang "en")
(def default-module :en$datetime) ; for repl usage
(def default-context {:reference-time (time/local-date-time [2013 2 12 4 30])})

(defn get-classifier
  [id]
  (when id
    (get @classifiers-map (keyword id))))

(defn get-rules
  [id]
  (when id
    (get @rules-map (keyword id))))

(defn compare-tokens
  "Compares two candidate tokens a and b for runtime selection.
  wanted-dim is a hash whose keys are the :dim wanted by the caller, the value
  can be anything truthy.
  Returns nil: not comparable 0: equal 1: greater -1: lesser"
  [a b classifiers wanted-dims]
  {:pre [(map? classifiers)]}
  (let [same-dim (= (:dim a) (:dim b))
        wanted-a (get wanted-dims (:dim a))
        wanted-b (get wanted-dims (:dim b))
        cmp-interval (util/compare-intervals
                       [(:pos a) (:end a)]
                       [(:pos b) (:end b)])] ; +1 0 -1 nil
  (if-not same-dim
    ; unless a is wanted and covers b, or the contrary, they are not comparable
    (cond (and wanted-a (= 1 cmp-interval)) 1
          (and wanted-b (= -1 cmp-interval)) -1
          :else nil)
    (if (not= 0 cmp-interval)
      cmp-interval ; one interval recovers the other
      (let [pa (learn/route-prob a classifiers) ; same interval
            pb (learn/route-prob b classifiers)]
        (compare pa pb))))))

(defn parse
  "Parse a sentence. Returns the stash and a curated list of winners.
   Targets is a coll of {:dim dim :label label} : only winners of these dims are
   kept, and they receive a :label key = the label provided.
   If no targets specified, all winners are returned."
  [s context module & [targets]]
  {:pre [s context module]}
  (let [classifiers (get-classifier module)
        _ (when-not (map? classifiers)
            (warnf "[picsou] classifiers is not a map s=%s module=%s targets=%s"
                   s module (util/spprint targets)))
        rules (get-rules module)
        stash (engine/pass-all s rules)
        dim-label (when targets (into {} (for [{:keys [dim label]} targets]
                                           [(keyword dim) label])))
        winners (->> stash
                     (filter :pos)
                     ; just keep the dims we want, and add the label key
                     (?>> dim-label map #(when-let [label (get dim-label (:dim %))]
                                          (assoc % :label label)))
                     (remove nil?)
                     ; resolve tokens value (one token can have 0 to n resolutions)
                     (mapcat #(engine/resolve-token % context module))
                     ; remove non-resolved token
                     (remove :not-resolved)
                     ; remove tokens who are recovered by tokens of same dim,
                     ; or share same interval (and same dim) but stronger probability
                     (util/keep-partial-max #(compare-tokens %1 %2 classifiers dim-label))
                     ; add a confidence key
                     ; low confidence for numbers covered by datetime
                     (engine/estimate-confidence context module)
                     ; adapt the keys for the outside world
                     (map #(merge % {:start (:pos %)
                                     :end (:end %)
                                     :body (:text %)})))]
    {:stash stash :winners winners}))

(defn print-stash
  "Print stash to STDOUT"
  ([stash] (print-stash stash default-module))
  ([stash lang] (print-stash stash lang (get-classifier lang)))
  ([stash lang classifiers]
    (let [width (count (:text (first stash)))]
      (doseq [[tok i] (reverse (map vector stash (iterate inc 0)))]
        (let [pos (:pos tok)
              end (:end tok)]
          (if pos
            (println
              (format "%s%s%s %2d | %-9s | %-25s | P = % 04.4f | %s"
                (apply str (repeat pos \space))
                (apply str (repeat (- end pos) \-))
                (apply str (repeat (- width end -1) \space))
                i
                (when-let [x (:dim tok)] (name x))
                (when-let [x (-> tok :rule :name )] (name x))
                (float (learn/route-prob tok classifiers))
                (strings/join " + " (mapv #(get-in % [:rule :name ]) (:route tok)))))
            (println (:text tok))))))))

(defn print-tokens
  "Recursively prints a tree representing a route"
  ([tokens]
    (print-tokens tokens default-module))
  ([tokens lang]
    (print-tokens tokens lang (get-classifier lang)))
  ([tokens lang classifiers]
    {:pre [(coll? tokens)]}
    (let [tokens (if (vector? tokens)
                   tokens
                   [tokens])
          tokens (if (= 1 (count tokens))
                   tokens
                   [{:route tokens :rule {:name "root"}}])]
      (print-tokens tokens lang classifiers 0 "")))
  ([tokens lang classifiers depth prefix]
    (doseq [[token i] (map vector tokens (iterate inc 1))]
      (let [;; determine name to display
            name (if-let [name (get-in token [:rule :name ])]
                   name
                   (str "text: " (:text token)))
            p (learn/route-prob token classifiers)
            ;; prepare children prefix
            last? (= i (count tokens))
            new-prefix (if last? \space \|)
            new-prefix (str prefix new-prefix \space \space \space)]
        (when (pos? depth)
          (print (format "%s%s-- "
                   prefix
                   (if last? \` \|))))
        (println (format "%s (%s)" name p))
        (print-tokens (:route token)
          lang
          classifiers
          (inc depth)
          (if (pos? depth) new-prefix ""))))))

;; default context is the same as the corpus context
(defn play
  "Show processing details for one sentence. Defines a 'details' function."
  ([s]
    (play s default-module))
  ([s lang]
    (play s lang nil))
  ([s lang targets]
    (play s lang targets default-context))
  ([s lang targets context]
    (let [targets (when targets (map (fn [dim] {:dim dim :label dim}) targets))
          {stash :stash
           winners :winners} (parse s context lang targets)]
      ;; 1. print stash
      (print-stash stash lang)
      (printf "%d tokens in stash\n" (count stash))

      ;; 2. print winners
      (print "Winners: ")
      (doseq [winner winners]
        (case (:dim winner)
          :time (println "Time" (:value winner))
          :duration (println "Duration" (select-keys winner [:grain :fuzzy :units :val]))
          :number (println "Number" "integer?" (:integer winner) (:value winner) (:body winner))
          :pnl (println "Potential named location: " (:pnl winner) " Within n :" (:n winner))
          :unit (println "Unit :" (:cat winner) " => " (:val winner))
          (println "Other: " (:dim winner) (:val winner)))
        (when (:latent winner) (println "Latent token"))
        (print-tokens winner lang))

      ;; 3. ask for details
      (println)
      (println (format "For further info: (details idx) where 0 < idx < %d" (dec (count stash))))
      (def details (fn [n]
                     (print-tokens (nth stash n) lang (get-classifier lang))
                     (println (nth stash n))))
      (def token (fn [n]
                   (nth stash n))))))

(defn run-corpus
  "Run the corpus"
  ([data]
    (run-corpus data default-module))
  ([{context :context, tests :tests} lang]
    (for [test tests
          text (:text test)]
      (try
        (let [stash (engine/pass-all text (get-rules lang))
              ;; _ (prn (get-classifier lang))
              winners (->> stash
                        (filter :pos )
                        (util/keep-partial-max
                          #(compare-tokens %1 %2 (get-classifier lang) {})))
              winner (when (= 1 (count winners)) (first winners))
              winner-count (count winners)
              ;; in this context, several winners means failure
              check (first (:checks test))]
          (if (some #(check % context) winners)
            [0 (str "OK  " (str "\"" text "\""))]
            [1 (str "FAIL" (str "\"" text "\"") " none of the " winner-count " winners did pass the test")]))
        (catch Exception e
          [1 (str "FAIL caught" (.getMessage e))])))))

(defn show-corpus
  "Runs the corpus and prints the results to the terminal."
  ([] (show-corpus default-module))
  ([lang]
    (let [output (run-corpus (lang @corpus-map) lang)]
      (doseq [line output]
        (println (second line)))
      (printf "%d examples, %d failed.\n" (count output) (->> output (map first) (reduce +))))))

(defn merge-rules
  [current config-key new-file]
  (let [new-file (io/resource (str "picsou/rules/" new-file ".clj"))
        new-rules (engine/rules (read-string (slurp new-file)))]
    (assoc
      current
      config-key
      (concat (config-key current) new-rules))))

(defn- merge-corpus
  [current config-key new-file]
  (let [new-file (io/resource (str "picsou/corpus/" new-file ".clj"))
        new-corpus (corpus/read-corpus new-file)]
    (assoc
      current
      config-key
      (util/merge-according-to {:tests concat :context merge} (config-key current) new-corpus))))

(defn reload!
  "Reload all corpus, rules and classifiers"
  [config]
  (reset! rules-map {})
  (reset! corpus-map {})
  (reset! classifiers-map {})
  (doseq [[config-key {corpus-files :corpus rules-files :rules}] config]
    (info "Loading picsou config " config-key)
    (doseq [corpus-file corpus-files]
      (swap! corpus-map merge-corpus config-key corpus-file))
    (doseq [rules-file rules-files]
      (swap! rules-map merge-rules config-key rules-file)))
  (doseq [[config-key rules] @rules-map]
    (swap! classifiers-map assoc config-key
                                 (learn/train-classifiers (get @corpus-map config-key)
                                                          rules
                                                          learn/extract-route-features))))

(defn extract
  "Public API. Leven-stash is ignored for the moment.
   targets is a coll of maps {:module :dim :label} for instance:
   {:module fr$datetime, :dim duration, :label wit$duration} to get duration results
   Returns a single coll of tokens with :body :value :start :end :label (=wisp) :latent"
  [sentence context leven-stash targets]
  {:pre [(string? sentence)
         (map? context)
         (:reference-time context)
         (vector? targets)]}
  (try
    (infof "Extracting from '%s' with targets %s" sentence targets)
    (letfn [(extract'
              [module targets] ; targets specify all the dims we should extract
              (let [module (keyword module)
                    module (if (module @rules-map)
                             module
                             (keyword (str default-lang "$" (-> module str (strings/split #"\$") second))))]
                (when-not (module @rules-map)
                  (throw (ex-info "Unknown picsou config" {:module module})))
                (->> (parse sentence context module targets)
                     :winners
                     (map #(select-keys % [:label :body :value :start :end :latent])))))]
      (->> targets
           (group-by :module) ; we want to run each config only once
           (mapcat (fn [[module targets]] (extract' module targets)))
           vec))
    (catch Exception e
      (let [err {:e e
                 :sentence sentence
                 :context context
                 :leven-stash leven-stash
                 :targets targets}]
        (errorf "picsou error err=%s" (pr-str err))
        (.printStackTrace e)
        []))))