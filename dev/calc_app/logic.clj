(ns calc-app.logic
  "Calculator state machine.

  State atom keys:
    :display  — string currently shown on the display
    :operand  — pending left-hand Double, or nil
    :op       — pending operator (:add :sub :mul :div), or nil
    :reset?   — when true, the next digit press clears :display first")

(defonce ^{:doc "Calculator state atom."} state
  (atom {:display "0"
         :operand nil
         :op      nil
         :reset?  true}))

(defn- display-num
  "Format a Double for display: strip trailing .0 for whole numbers."
  [n]
  ;; Double.toString emits "24.0" for integers — strip the suffix when present.
  ;; Avoids Math/floor, Math/abs, and finite? (none available in SCI).
  (let [s (str n)]
    (if (re-matches #"-?\d+\.0" s)
      (subs s 0 (- (count s) 2))
      s)))

(defn- apply-op
  "Apply pending op to operand and cur, returning a Double result."
  [op operand cur]
  (case op
    :add (+ operand cur)
    :sub (- operand cur)
    :mul (* operand cur)
    :div (if (zero? cur) ##Inf (/ operand cur))))

(defn press-digit!
  "Append digit d (string \"0\"–\"9\") to the display."
  [d]
  (swap! state
         (fn [{:keys [display reset?] :as s}]
           (assoc s
                  :display (if reset? (str d) (str display d))
                  :reset? false))))

(defn press-dot!
  "Append decimal point to the display (no-op if already present)."
  []
  (swap! state
         (fn [{:keys [display reset?] :as s}]
           (let [base (if reset? "0" display)]
             (if (clojure.string/includes? base ".")
               (assoc s :display base :reset? false)
               (assoc s :display (str base ".") :reset? false))))))

(defn press-op!
  "Record a binary operator. If a pending op exists, apply it first."
  [new-op]
  (swap! state
         (fn [{:keys [display operand op] :as s}]
           (let [cur (Double/parseDouble display)
                 val (if (and operand op)
                       (apply-op op operand cur)
                       cur)]
             (assoc s
                    :operand val
                    :op new-op
                    :display (display-num val)
                    :reset? true)))))

(defn press-equals!
  "Evaluate the pending operation and show the result."
  []
  (swap! state
         (fn [{:keys [display operand op] :as s}]
           (if (and operand op)
             (let [cur (Double/parseDouble display)
                   val (apply-op op operand cur)]
               (assoc s
                      :display (display-num val)
                      :operand nil
                      :op nil
                      :reset? true))
             s))))

(defn press-clear!
  "Reset calculator to initial state."
  []
  (reset! state {:display "0" :operand nil :op nil :reset? true}))

(defn press-fn!
  "Apply a unary function f (Double → Double) to the current display value."
  [f]
  (swap! state
         (fn [{:keys [display] :as s}]
           (let [x   (Double/parseDouble display)
                 val (f x)]
             (assoc s :display (display-num val) :reset? true)))))
