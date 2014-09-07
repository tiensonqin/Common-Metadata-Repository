(ns cmr.search.data.complex-to-simple-converters.orbit
  "Defines functions that implement the reduce-query method of the ComplexQueryToSimple
  protocol for orbit related search fields."
  (:require [cmr.search.models.query :as qm]
            [cmr.search.data.complex-to-simple :as c2s]
            [cmr.common.services.errors :as errors]
            [cmr.search.data.messages :as m]))

(defn- orbit-number-range-condition-both
  "Creates a grouped condition from an OrbitNumberRangeCondition with both min-value and max.'"
  [min-value max-value]
  (let [start-orbit-number-range-cond (qm/numeric-range-condition :start-orbit-number min-value max-value)
        orbit-number-range-cond (qm/numeric-range-condition :orbit-number min-value max-value)
        stop-orbit-number-range-cond (qm/numeric-range-condition :stop-orbit-number min-value max-value)
        min-inside-start-cond (qm/numeric-range-condition :start-orbit-number nil min-value)
        min-inside-stop-cond (qm/numeric-range-condition :stop-orbit-number min-value nil)
        min-and-clause (qm/and-conds [min-inside-start-cond min-inside-stop-cond])
        max-inside-start-cond (qm/numeric-range-condition :start-orbit-number nil max-value)
        max-inside-stop-cond (qm/numeric-range-condition :stop-orbit-number max-value nil)
        max-and-clause (qm/and-conds [max-inside-start-cond max-inside-stop-cond])]
    (qm/or-conds [start-orbit-number-range-cond
                  orbit-number-range-cond
                  stop-orbit-number-range-cond
                  min-and-clause
                  max-and-clause])))

(defn- orbit-number-range-condition-min
  "Creates a grouped condition with just the min-value specified."
  [min-value]
  (let [stop-orbit-number-range-cond (qm/numeric-range-condition :stop-orbit-number min-value nil)
        orbit-number-range-cond (qm/numeric-range-condition :orbit-number min-value nil)]
    (qm/or-conds [stop-orbit-number-range-cond orbit-number-range-cond])))


(defn- orbit-number-range-condition-max
  "Creates a grouped condition with just the max specified."
  [max-value]
  (let [start-orbit-number-range-cond (qm/numeric-range-condition :start-orbit-number nil max-value)
        orbit-number-range-cond (qm/numeric-range-condition :orbit-number nil max-value)]
    (qm/or-conds [start-orbit-number-range-cond orbit-number-range-cond])))

(defn- equator-crossing-longitude-condition-both
  "Creates a grouped condition from an EquatorCrossingLongitudeCondition with both min-value and
  max-value.'"
  [min-value max-value]
  (if (>= max-value min-value)
    (qm/numeric-range-condition :equator-crossing-longitude min-value max-value)

    ;; If the lower bound is higher than the upper bound then we need to construct two ranges
    ;; to allow us to cross the 180/-180 boundary)
    (let [lower-query (qm/numeric-range-condition :equator-crossing-longitude min-value 180.0)
          upper-query (qm/numeric-range-condition :equator-crossing-longitude -180.0 max-value)]
      (qm/or-conds [lower-query upper-query]))))

(defn- equator-crossing-longitude-condition-min
  "Creates a grouped condition with just the min-value specified."
  [min-value]
  (qm/numeric-range-condition :equator-crossing-longitude min-value nil))

(defn- equator-crossing-longitude-condition-max
  "Creates a grouped condition with just the max specified."
  [max-value]
  (qm/numeric-range-condition :equator-crossing-longitude nil max-value))


(extend-protocol c2s/ComplexQueryToSimple
  cmr.search.models.query.OrbitNumberValueCondition
  (c2s/reduce-query
    [condition context]
    (let [orbit-number (:value condition)
          term-condition (qm/map->NumericValueCondition {:field :orbit-number :value orbit-number})
          start-range-cond (qm/numeric-range-condition :start-orbit-number nil orbit-number)
          stop-range-cond (qm/numeric-range-condition :stop-orbit-number orbit-number nil)
          and-clause (qm/and-conds [start-range-cond stop-range-cond])
          or-clause (qm/or-conds [term-condition and-clause])]
      (qm/nested-condition :orbit-calculated-spatial-domains or-clause)))


  cmr.search.models.query.OrbitNumberRangeCondition
  (c2s/reduce-query
    [condition context]
    (let [{:keys [min-value max-value]} condition
          group-condtion (cond
                           (and min-value max-value)
                           (orbit-number-range-condition-both min-value max-value)

                           min-value
                           (orbit-number-range-condition-min min-value)

                           max-value
                           (orbit-number-range-condition-max max-value)

                           :else
                           (errors/internal-error! (m/nil-min-max-msg)))]
      (qm/nested-condition :orbit-calculated-spatial-domains group-condtion)))

  cmr.search.models.query.EquatorCrossingLongitudeCondition
  (c2s/reduce-query
    [condition context]
    (let [{:keys [min-value max-value]} condition
          group-condition (cond
                            (and min-value max-value)
                            (equator-crossing-longitude-condition-both min-value max-value)

                            min-value
                            (equator-crossing-longitude-condition-min min-value)

                            max-value
                            (equator-crossing-longitude-condition-max max-value)

                            :else
                            (errors/internal-error! (m/nil-min-max-msg)))]
      (qm/nested-condition :orbit-calculated-spatial-domains group-condition)))

  cmr.search.models.query.EquatorCrossingDateCondition
  (c2s/reduce-query
    [condition context]
    (let [{:keys [start-date end-date]} condition
          range-cond (qm/date-range-condition :equator-crossing-date-time start-date end-date)]
      (qm/nested-condition :orbit-calculated-spatial-domains range-cond))))