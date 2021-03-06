(ns metabase.models.segment-test
  (:require [expectations :refer :all]
            (metabase.models [database :refer [Database]]
                             [hydrate :refer :all]
                             [segment :refer :all]
                             [table :refer [Table]])
            [metabase.test.data :refer :all]
            [metabase.test.data.users :refer :all]
            [metabase.test.util :as tu]
            [metabase.util :as u]))

(defn- user-details
  [username]
  (dissoc (fetch-user username) :date_joined :last_login))

(defn- segment-details
  [{:keys [creator], :as segment}]
  (-> segment
      (dissoc :id :table_id :created_at :updated_at)
      (assoc :creator (dissoc creator :date_joined :last_login))))

(defn- create-segment-then-select!
  [table name description creator definition]
  (segment-details (create-segment! table name description creator definition)))

(defn- update-segment-then-select!
  [segment]
  (segment-details (update-segment! segment (user->id :crowberto))))


;; create-segment!
(expect
  {:creator_id  (user->id :rasta)
   :creator     (user-details :rasta)
   :name        "I only want *these* things"
   :description nil
   :is_active   true
   :definition  {:clause ["a" "b"]}}
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]]
    (create-segment-then-select! table-id "I only want *these* things" nil (user->id :rasta) {:clause ["a" "b"]})))


;; exists-segment?
(expect
  [true
   false]
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id}   {:db_id database-id}]
                  Segment  [{segment-id :id} {:table_id table-id}]]
    [(exists-segment? segment-id)
     (exists-segment? 3400)]))


;; retrieve-segment
(expect
  {:creator_id   (user->id :rasta)
   :creator      (user-details :rasta)
   :name         "Toucans in the rainforest"
   :description  "Lookin' for a blueberry"
   :is_active    true
   :definition   {:database 45
                  :query    {:filter ["yay"]}}}
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id}   {:db_id database-id}]
                  Segment  [{segment-id :id} {:table_id   table-id
                                              :definition {:database 45
                                                           :query    {:filter ["yay"]}}}]]
    (let [{:keys [creator] :as segment} (retrieve-segment segment-id)]
      (-> segment
          (dissoc :id :table_id :created_at :updated_at)
          (assoc :creator (dissoc creator :date_joined :last_login))))))


;; retrieve-segements
(expect
  [{:creator_id   (user->id :rasta)
    :creator      (user-details :rasta)
    :name         "Segment 1"
    :description  nil
    :is_active    true
    :definition   {}}]
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{table-id-1 :id}    {:db_id database-id}]
                  Table    [{table-id-2 :id}    {:db_id database-id}]
                  Segment  [{segement-id-1 :id} {:table_id table-id-1, :name "Segment 1", :description nil}]
                  Segment  [{segment-id-2 :id}  {:table_id table-id-2}]
                  Segment  [{segment-id3 :id}   {:table_id table-id-1, :is_active false}]]
    (doall (for [segment (u/prog1 (retrieve-segments table-id-1)
                                  (assert (= 1 (count <>))))]
             (-> (dissoc (into {} segment) :id :table_id :created_at :updated_at)
                 (update :creator (u/rpartial dissoc :date_joined :last_login)))))))


;; update-segment!
;; basic update.  we are testing several things here
;;  1. ability to update the Segment name
;;  2. creator_id cannot be changed
;;  3. ability to set description, including to nil
;;  4. ability to modify the definition json
;;  5. revision is captured along with our commit message
(expect
  {:creator_id   (user->id :rasta)
   :creator      (user-details :rasta)
   :name         "Costa Rica"
   :description  nil
   :is_active    true
   :definition   {:database 2
                  :query    {:filter ["not" "the toucans you're looking for"]}}}
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{:keys [id]} {:db_id database-id}]
                  Segment  [{:keys [id]} {:table_id id}]]
    (update-segment-then-select! {:id          id
                                 :name        "Costa Rica"
                                 :description nil
                                 :creator_id  (user->id :crowberto)
                                 :table_id    456
                                 :definition  {:database 2
                                               :query    {:filter ["not" "the toucans you're looking for"]}}
                                 :revision_message "Just horsing around"})))

;; delete-segment!
(expect
  {:creator_id   (user->id :rasta)
   :creator      (user-details :rasta)
   :name         "Toucans in the rainforest"
   :description  "Lookin' for a blueberry"
   :is_active    false
   :definition   {}}
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{:keys [id]} {:db_id database-id}]
                  Segment  [{:keys [id]} {:table_id id}]]
    (delete-segment! id (user->id :crowberto) "revision message")
    (segment-details (retrieve-segment id))))


;; ## Segment Revisions

(tu/resolve-private-fns metabase.models.segment serialize-segment diff-segments)

;; serialize-segment
(expect
  {:id          true
   :table_id    true
   :creator_id  (user->id :rasta)
   :name        "Toucans in the rainforest"
   :description "Lookin' for a blueberry"
   :definition  {:filter ["AND",[">",4,"2014-10-19"]]}
   :is_active   true}
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]
                  Segment  [segment        {:table_id   table-id
                                            :definition {:filter ["AND" [">" 4 "2014-10-19"]]}}]]
    (-> (serialize-segment Segment (:id segment) segment)
        (update :id boolean)
        (update :table_id boolean))))


;; diff-segments
(expect
  {:definition  {:before {:filter ["AND" [">" 4 "2014-10-19"]]}
                 :after  {:filter ["AND" ["BETWEEN" 4 "2014-07-01" "2014-10-19"]]}}
   :description {:before "Lookin' for a blueberry"
                 :after  "BBB"}
   :name        {:before "Toucans in the rainforest"
                 :after  "Something else"}}
  (tu/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]
                  Segment  [segment        {:table_id   table-id
                                            :definition {:filter ["AND" [">" 4 "2014-10-19"]]}}]]
    (diff-segments Segment segment (assoc segment
                                          :name        "Something else"
                                          :description "BBB"
                                          :definition  {:filter ["AND" ["BETWEEN" 4 "2014-07-01" "2014-10-19"]]}))))

;; test case where definition doesn't change
(expect
  {:name {:before "A"
          :after  "B"}}
  (diff-segments Segment
                 {:name        "A"
                  :description "Unchanged"
                  :definition  {:filter ["AND" [">" 4 "2014-10-19"]]}}
                 {:name        "B"
                  :description "Unchanged"
                  :definition  {:filter ["AND" [">" 4 "2014-10-19"]]}}))

;; first version  so comparing against nil
(expect
  {:name        {:after  "A"}
   :description {:after "Unchanged"}
   :definition  {:after {:filter ["AND" [">" 4 "2014-10-19"]]}}}
  (diff-segments Segment
                 nil
                 {:name        "A"
                  :description "Unchanged"
                  :definition  {:filter ["AND" [">" 4 "2014-10-19"]]}}))

;; removals only
(expect
  {:definition  {:before {:filter ["AND" [">" 4 "2014-10-19"] ["=" 5 "yes"]]}
                 :after  {:filter ["AND" [">" 4 "2014-10-19"]]}}}
  (diff-segments Segment
                 {:name        "A"
                  :description "Unchanged"
                  :definition  {:filter ["AND" [">" 4 "2014-10-19"] ["=" 5 "yes"]]}}
                 {:name        "A"
                  :description "Unchanged"
                  :definition  {:filter ["AND" [">" 4 "2014-10-19"]]}}))
