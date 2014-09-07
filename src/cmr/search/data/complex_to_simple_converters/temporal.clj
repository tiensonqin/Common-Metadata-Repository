(ns cmr.search.data.complex-to-simple-converters.temporal
  "Defines functions that implement the reduce-query method of the ComplexQueryToSimple
  protocol for Temporal conditions."

  (:require [clj-time.core :as t]
            [cmr.search.models.query :as qm]
            [cmr.search.services.parameters.converters.temporal :as temporal]
            [cmr.search.data.complex-to-simple :as c2s]))

(defn- intersect-temporal->simple-conditions
  "Convert a temporal condition with INTERSECT mask into a combination of simpler conditions
  so that it will be easier to convert into elastic json"
  [temporal]
  (let [{:keys [start-date end-date]} (:date-range-condition temporal)
        conditions (if end-date
                     [(qm/map->DateRangeCondition {:field :start-date
                                                   :end-date end-date})
                      (qm/or-conds [(qm/map->MissingCondition {:field :end-date})
                                    (qm/map->DateRangeCondition {:field :end-date
                                                                 :start-date start-date})])]
                     [(qm/or-conds [(qm/map->MissingCondition {:field :end-date})
                                    (qm/map->DateRangeCondition {:field :end-date
                                                                 :start-date start-date})])])]
    (qm/and-conds (concat
                    [(qm/map->ExistCondition {:field :start-date})]
                    conditions))))

(defn current-end-date
  "Returns the current end datetime for a given year and attributes of a periodic temporal condition"
  [current-year end-date start-day end-day end-year]
  (cond
    (nil? end-day)
    (if (and (= current-year end-year) end-date)
      end-date
      (t/minus (t/date-time (inc current-year)) (t/seconds 1)))

    ;; recurring range does not cross over a year boundary - for example January 25 - May 6
    (> end-day start-day)
    (let [current-end (t/plus (t/date-time current-year) (t/days (dec end-day)))]
      (if (and end-date (t/after? current-end end-date))
        end-date
        current-end))

    ;; recurring range does cross over a year boundary - for example October 1 - March 10
    :else
    (let [current-end (t/plus (t/date-time (inc current-year)) (t/days (dec end-day)))]
      (if (and end-date (t/after? current-end end-date))
        end-date
        current-end))))

(defn- simple-conditions-for-year
  "Returns simple-conditions constructed for a given year with the periodic temporal condition"
  [current-year start-date end-date start-day end-day end-year]
  (let [current-start (t/plus (t/date-time current-year) (t/days (dec start-day)))
        current-start (if (t/before? current-start start-date) start-date current-start)
        current-end (current-end-date current-year end-date start-day end-day end-year)]
    (when-not (t/before? current-end current-start)
      (intersect-temporal->simple-conditions
        (temporal/map->temporal-condition {:field :temporal
                                           :start-date current-start
                                           :end-date current-end})))))

(defn- periodic-temporal->simple-conditions
  "Convert a periodic temporal condition into a combination of simpler conditions
  so that it will be easier to convert into elastic json"
  [temporal]
  (let [{{:keys [start-date end-date]} :date-range-condition
         :keys [start-day end-day]} temporal
        end-year (if end-date (t/year end-date) (t/year (t/now)))
        start-day (if start-day start-day 1)
        conditions (map
                     #(simple-conditions-for-year
                        % start-date end-date start-day end-day end-year)
                     (range (t/year start-date) (inc end-year)))]
    (qm/or-conds (remove nil? conditions))))

(defn- temporal->simple-conditions
  "Convert a temporal condition into a combination of simpler conditions so that it will be easier to convert into elastic json"
  [temporal]
  (let [{:keys [start-day end-day]} temporal]
    (if (or start-day end-day)
      (periodic-temporal->simple-conditions temporal)
      (intersect-temporal->simple-conditions temporal))))

;; Reduce a Temporal condition to simpler conditions
(extend-protocol c2s/ComplexQueryToSimple
  cmr.search.models.query.TemporalCondition
  (c2s/reduce-query
    [condition context]
    (temporal->simple-conditions condition)))
