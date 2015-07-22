(ns cmr.system-int-test.search.concept-metadata-search-test
  "Integration test for retrieving collection metadata from search by concept-id and revision-id"
  (:require [clojure.test :refer :all]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.granule :as dg]
            [cmr.system-int-test.data2.core :as d]
            [cmr.umm.echo10.collection :as c]
            [cmr.common.util :refer [are2] :as util]
            [cmr.transmit.config :as transmit-config]
            [cmr.common.mime-types :as mt]
            [cmr.umm.core :as umm]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.system-int-test.system :as s]
            [clj-time.format :as f]))

(use-fixtures
  :each
  (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"} true))

(deftest retrieve-metadata-from-search-by-concept-id-concept-revision
  ;; Grant permissions before creating data
  ;; all collections in prov1 granted to guests
  (e/grant-registered-users (s/context) (e/coll-catalog-item-id "provguid1"))
  (e/grant-registered-users (s/context) (e/gran-catalog-item-id "provguid1"))

  (let [umm-coll1-1 (dc/collection {:entry-title "et1"
                                    :entry-id "s1_v1"
                                    :version-id "v1"
                                    :short-name "s1"})
        umm-coll1-2 (-> umm-coll1-1
                        (assoc-in [:product :version-id] "v2")
                        (assoc :entry-id "s1_v2"))
        umm-coll2-1 (dc/collection {:entry-title "et2"
                                    :entry-id "s2_v2"
                                    :version-id "v2"
                                    :short-name "s2"})
        umm-coll2-3 (-> umm-coll2-1
                        (assoc-in [:product :version-id] "v6")
                        (assoc :entry-id "s2_v6"))

        umm-gran1-1 (dg/granule umm-coll2-1 {:access-value 1.0})
        umm-gran1-2 (assoc umm-gran1-1 :access-value 2.0)

        ;; NOTE - most of the following bindings could be ignored with _, but they are assigned
        ;; to vars to make it easier to see what is being ingested.

        ;; Ingest a collection twice.
        coll1-1 (d/ingest "PROV1" umm-coll1-1)
        coll1-2 (d/ingest "PROV1" umm-coll1-2)

        ;; Ingest collection once, delete, then ingest again.
        coll2-1 (d/ingest "PROV1" umm-coll2-1)
        _ (ingest/delete-concept (d/item->concept coll2-1))
        coll2-3 (d/ingest "PROV1" umm-coll2-3)

        ;; Ingest a collection for PROV2 that is not visible to guests.
        coll3 (d/ingest "PROV2" (dc/collection {:entry-title "et1"
                                                :version-id "v1"
                                                :short-name "s1"}))
        ;; ingest granule twice
        gran1-1 (d/ingest "PROV1" umm-gran1-1)
        gran1-2 (d/ingest "PROV1" umm-gran1-2)

        guest-token (e/login-guest (s/context))
        user1-token (e/login (s/context) "user1")]
    (index/wait-until-indexed)

    (testing "retrieve metadata from search by concept-id/revision-id"
      (testing "collections and granules"
        (are2 [item format-key accept concept-id revision-id]
              (let [headers {transmit-config/token-header user1-token
                             "Accept" accept}
                    response (search/find-concept-metadata-by-id-and-revision
                               concept-id
                               revision-id
                               {:headers headers})
                    expected (:metadata (d/item->metadata-result false format-key item))]
                (is (= expected (:body response))))

              "echo10 collection revision 1"
              umm-coll1-1 :echo10 "application/echo10+xml" "C1200000000-PROV1" 1

              "echo10 collection revision 2"
              umm-coll1-2 :echo10 "application/echo10+xml" "C1200000000-PROV1" 2

              "dif collection revision 1"
              umm-coll2-1 :dif "application/dif+xml" "C1200000001-PROV1" 1

              "dif collection revision 3"
              umm-coll2-3 :dif "application/dif+xml" "C1200000001-PROV1" 3

              "dif10 collection revision 1"
              umm-coll2-1 :dif10 "application/dif10+xml" "C1200000001-PROV1" 1

              "dif10 collection revision 3"
              umm-coll2-3 :dif10 "application/dif10+xml" "C1200000001-PROV1" 3

              "iso-smap collection revision 1"
              umm-coll1-1 :iso-smap "application/iso:smap+xml" "C1200000000-PROV1" 1

              "iso-smap collection revision 2"
              umm-coll1-2 :iso-smap "application/iso:smap+xml" "C1200000000-PROV1" 2

              "iso19115 collection revision 1"
              umm-coll2-1 :iso19115 "application/iso19115+xml" "C1200000001-PROV1" 1

              "iso19115 collection revision 3"
              umm-coll2-3 :iso19115 "application/iso19115+xml" "C1200000001-PROV1" 3

              " echo10 granule revision 1"
              umm-gran1-1 :echo10 "application/echo10+xml" "G1200000003-PROV1" 1

              " echo10 granule revision 2"
              umm-gran1-2 :echo10 "application/echo10+xml" "G1200000003-PROV1" 2))

      (testing "Requests for tombstone revision returns a 400 error"
        (let [{:keys [status errors] :as response} (search/get-search-failure-xml-data
                                        (search/find-concept-metadata-by-id-and-revision
                                                  (:concept-id coll2-1)
                                                  2
                                                  {:headers {transmit-config/token-header
                                                             user1-token}}))]
          (is (= 400 status))
          (is (= #{"Deleted concepts do not contain metadata"}
                 (set errors)))))

      (testing "Unknown concept-id returns a 404 error"
        (let [{:keys [status errors]} (search/get-search-failure-xml-data
                                        [(search/find-concept-metadata-by-id-and-revision
                                                  "C1234-PROV1"
                                                  1
                                                  {:headers {transmit-config/token-header
                                                             user1-token}})])]
          (is (= 404 status))
          (is (= #{"Concept with concept-id [C1234-PROV1] and revision-id [1] does not exist."}
                 (set errors)))))

      (testing "Known concept-id with unavailable revision-id returns a 404 error"
        (let [{:keys [status errors]} (search/get-search-failure-xml-data
                                        (search/find-concept-metadata-by-id-and-revision
                                                  "C1200000000-PROV1"
                                                  1000000
                                                  {:headers {transmit-config/token-header
                                                             user1-token}}))]
          (is (= 404 status))
          (is (= #{"Concept with concept-id [C1200000000-PROV1] and revision-id [1000000] does not exist."}
                 (set errors)))))

      (testing "ACLs"
        ;; no token
        (let [{:keys [status errors]} (search/get-search-failure-xml-data
                                        (search/find-concept-metadata-by-id-and-revision
                                          "C1200000001-PROV1"
                                          1
                                          {}))]
          (is (= 404 status))
          (is(= #{"Concept with concept-id [C1200000001-PROV1] and revision-id [1] could not be found."}
                (set errors))))
        ;; Guest token can't see PROV2 collections.
        (let [{:keys [status errors]} (search/get-search-failure-xml-data
                                        (search/find-concept-metadata-by-id-and-revision
                                          "C1200000002-PROV2"
                                          1
                                          {:headers {transmit-config/token-header
                                                     guest-token}}))]
          (is (= 404 status))
          (is(= #{"Concept with concept-id [C1200000002-PROV2] and revision-id [1] could not be found."}
                (set errors))))))))
