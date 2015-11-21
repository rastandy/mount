(ns mount.core
  (:require [clojure.tools.macro :as macro]
            [clojure.tools.namespace.repl :refer [disable-reload!]]
            [clojure.tools.logging :refer [info warn debug error]]))

(disable-reload!)

;; (defonce ^:private session-id (System/currentTimeMillis))
(defonce ^:private mount-state 42)
(defonce ^:private -args (atom :no-args))                  ;; mostly for command line args and external files
(defonce ^:private state-seq (atom 0))
(defonce ^:private state-order (atom {}))

(defn- make-state-seq [state]
  (or (@state-order state)
      (let [nseq (swap! state-seq inc)]
        (swap! state-order assoc state nseq)
        nseq)))

(deftype NotStartedState [state] 
  Object 
  (toString [this] 
    (str "'" state "' is not started (to start all the states call mount/start)")))

;;TODO validate the whole lifecycle
(defn- validate [{:keys [start stop suspend resume] :as lifecycle}]
  (cond 
    (not start) (throw 
                  (IllegalArgumentException. "can't start a stateful thing without a start function. (i.e. missing :start fn)"))
    (and suspend (not resume)) (throw 
                                 (IllegalArgumentException. "suspendable state should have a resume function (i.e. missing :resume fn)"))))

(defmacro defstate [state & body]
  (let [[state params] (macro/name-with-attributes state body)
        {:keys [start stop suspend resume] :as lifecycle} (apply hash-map params)]
    (validate lifecycle)
    (let [s-meta (cond-> {:mount-state mount-state
                          :order (make-state-seq state)
                          :start `(fn [] (~@start)) 
                          :started? false}
                   stop (assoc :stop `(fn [] (~@stop)))
                   suspend (assoc :suspend `(fn [] (~@suspend)))
                   resume (assoc :resume `(fn [] (~@resume))))]
      `(defonce ~(with-meta state (merge (meta state) s-meta))
         (NotStartedState. ~(str state))))))

(defn- up [var {:keys [ns name start started? resume suspended?]}]
  (when-not started?
    (let [s (try (if suspended?
                   (do (info ">> resuming.. " name)
                       (resume))
                   (do (info ">> starting.. " name)
                       (start)))
                 (catch Throwable t
                   (throw (RuntimeException. (str "could not start [" name "] due to") t))))]
      (intern ns (symbol name) s)
      (alter-meta! var assoc :started? true :suspended? false))))

(defn- down [var {:keys [ns name stop started? suspended?]}]
  (when (or started? suspended?)
    (info "<< stopping.. " name)
    (when stop 
      (try
        (stop)
        (catch Throwable t
          (throw (RuntimeException. (str "could not stop [" name "] due to") t)))))
    (intern ns (symbol name) (NotStartedState. name)) ;; (!) if a state does not have :stop when _should_ this might leak
    (alter-meta! var assoc :started? false :suspended? false)))

(defn- sigstop [var {:keys [ns name started? suspend resume]}]
  (when (and started? resume)        ;; can't have suspend without resume, but the reverse is possible
    (info ">> suspending.. " name)
    (when suspend                    ;; don't suspend if there is only resume function (just mark it :suspended?)
      (let [s (try (suspend)
                   (catch Throwable t
                     (throw (RuntimeException. (str "could not suspend [" name "] due to") t))))]
        (intern ns (symbol name) s)))
    (alter-meta! var assoc :started? false :suspended? true)))

(defn- sigcont [var {:keys [ns name start started? resume suspended?]}]
  (when (instance? NotStartedState var)
    (throw (RuntimeException. (str "could not resume [" name "] since it is stoppped (i.e. not suspended)"))))
  (when suspended?
    (info ">> resuming.. " name)
    (let [s (try (resume)
                 (catch Throwable t
                   (throw (RuntimeException. (str "could not resume [" name "] due to") t))))]
      (intern ns (symbol name) s)
      (alter-meta! var assoc :started? true :suspended? false))))

;;TODO args might need more thinking
(defn args [] @-args)

(defn mount-state? [var]
  (= (-> var meta :mount-state)
     mount-state))

(defn find-all-states []
  (->> (all-ns)
       (mapcat ns-interns)
       (map second)
       (filter mount-state?)))

;;TODO ns based for now. need to be _state_ based
(defn- add-deps [{:keys [ns] :as state} all]
  (let [refers (ns-refers ns)
        any (set all)
        deps (filter (comp any val) refers)]
    (assoc state :deps deps)))

(defn states-with-deps []
  (let [all (find-all-states)]
    (->> (map (comp #(add-deps % all)
                    #(select-keys % [:name :order :ns :started? :suspended?])
                    meta)
              all)
         (sort-by :order))))

(defn- bring [states fun order]
  (->> states
       (sort-by (comp :order meta) order)
       (map #(fun % (meta %)))
       doall))

(defn merge-lifecycles 
  "merges with overriding _certain_ non existing keys. 
   i.e. :suspend is in a 'state', but not in a 'substitute': it should be overriden with nil
        however other keys of 'state' (such as :ns,:name,:order) should not be overriden"
  ([state sub]
    (merge-lifecycles state nil sub))
  ([state origin {:keys [start stop suspend resume suspended?]}]
    (assoc state :origin origin 
                 :suspended? suspended?
                 :start start :stop stop :suspend suspend :resume resume)))

(defn rollback! [state]
  (let [{:keys [origin]} (meta state)]
    (when origin
      (alter-meta! state #(merge-lifecycles % origin)))))

(defn substitute! [state with]
  (let [lifecycle-fns #(select-keys % [:start :stop :suspend :resume :suspended?])
        origin (meta state)
        sub (meta with)]
    (alter-meta! with assoc :sub? true)
    (alter-meta! state #(merge-lifecycles % (lifecycle-fns origin) sub))))

(defn- unsub [state]
  (when (-> (meta state) :sub?)
    (alter-meta! state assoc :sub? nil
                             :started false)))

(defn- all-without-subs []
  (remove (comp :sub? meta) (find-all-states)))

(defn start [& states]
  (let [states (or (seq states) (all-without-subs))]
    (bring states up <)
    :started))

(defn stop [& states]
  (let [states (or states (find-all-states))]
    (doall (map unsub states))     ;; unmark substitutions marked by "start-with"
    (bring states down >)
    (doall (map rollback! states)) ;; restore to origin from "start-with"
    :stopped))

(defn stop-except [& states]
  (let [all (set (find-all-states))
        states (remove (set states) all)]
    (doall (map unsub states))     ;; unmark substitutions marked by "start-with"
    (bring states down >)
    (doall (map rollback! states)) ;; restore to origin from "start-with"
    :stopped))

(defn start-with-args [xs & states]
  (reset! -args xs)
  (if (first states)
    (start states)
    (start)))

(defn start-with [with]
  (doall
    (for [[from to] with]
      (substitute! from to)))
  (start))

(defn start-without [& states]
  (if (first states)
    (let [app (set (all-without-subs))
          without (remove (set states) app)]
      (apply start without))
    (start)))

(defn suspend [& states]
  (let [states (or (seq states) (all-without-subs))]
    (bring states sigstop <)
    :suspended))

(defn resume [& states]
  (let [states (or (seq states) (all-without-subs))]
    (bring states sigcont <)
    :resumed))