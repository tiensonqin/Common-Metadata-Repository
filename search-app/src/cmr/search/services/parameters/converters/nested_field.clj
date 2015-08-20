(ns cmr.search.services.parameters.converters.nested-field
  "Contains functions for converting query parameters to conditions for nested fields."
  (:require [cmr.search.models.query :as qm]
            [cmr.search.models.group-query-conditions :as gc]
            [cmr.search.services.parameters.conversion :as p]
            [cmr.transmit.kms :as kms]))

(def nested-field-mappings
  {:science-keywords #{:category :topic :term :variable-level-1 :variable-level-2 :variable-level-3
                       :detailed-variable}
   :platforms (:platforms kms/keyword-scheme->field-names)})

(defn- nested-field->elastic-keyword
  "Returns the elastic keyword for the given nested field and subfield.

  Example:
  (nested-field->elastic-keyword :science-keywords :category) returns :science-keywords.category."
  [parent-field subfield]
  (keyword (str (name parent-field) "." (name subfield))))

(defn- nested-field+value->string-condition
  "Converts a science keyword field and value into a string condition"
  [parent-field subfield value case-sensitive? pattern?]
  (if (sequential? value)
    (qm/string-conditions (nested-field->elastic-keyword parent-field subfield) value
                          case-sensitive? pattern? :or)
    (qm/string-condition (nested-field->elastic-keyword parent-field subfield) value
                         case-sensitive? pattern?)))

(defn parse-nested-condition
  "Converts a nested condition into a nested query model condition."
  [parent-field query-map case-sensitive? pattern?]
  (qm/nested-condition
    parent-field
    (gc/and-conds
      (map (fn [[subfield-name subfield-value]]
             (if (= :any subfield-name)
               (gc/or-conds
                 (map #(nested-field+value->string-condition parent-field % subfield-value
                                                             case-sensitive? pattern?)
                      (parent-field nested-field-mappings)))
               (nested-field+value->string-condition parent-field subfield-name subfield-value
                                                     case-sensitive? pattern?)))
           (dissoc query-map :pattern :ignore-case)))))

