(ns metabase.driver.generic-sql
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.math.numeric-tower :as math]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            (honeysql [core :as hsql]
                      [format :as hformat])
            (korma [core :as k]
                   [db :as kdb])
            [metabase.driver :as driver]
            [metabase.sync-database.analyze :as analyze]
            metabase.query-processor.interface
            (metabase.models [field :as field]
                             raw-table
                             [table :as table])
            [metabase.util :as u]
            [metabase.util.honeysql-extensions :as hx])
  (:import java.sql.DatabaseMetaData
           java.util.Map
           (clojure.lang Keyword PersistentVector)
           com.mchange.v2.c3p0.ComboPooledDataSource
           (metabase.query_processor.interface Field Value)))

(defprotocol ISQLDriver
  "Methods SQL-based drivers should implement in order to use `IDriverSQLDefaultsMixin`.
   Methods marked *OPTIONAL* have default implementations in `ISQLDriverDefaultsMixin`."

  (active-tables ^java.util.Set [this, ^DatabaseMetaData metadata]
    "Return a set of maps containing information about the active tables/views, collections, or equivalent that currently exist in DATABASE.
     Each map should contain the key `:name`, which is the string name of the table. For databases that have a concept of schemas,
     this map should also include the string name of the table's `:schema`.")

  ;; The following apply-* methods define how the SQL Query Processor handles given query clauses. Each method is called when a matching clause is present
  ;; in QUERY, and should return an appropriately modified version of KORMA-QUERY. Most drivers can use the default implementations for all of these methods,
  ;; but some may need to override one or more (e.g. SQL Server needs to override the behavior of `apply-limit`, since T-SQL uses `TOP` instead of `LIMIT`).
  (apply-aggregation [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-breakout    [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-fields      [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-filter      [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-join-tables [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-limit       [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-order-by    [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-page        [this honeysql-form, ^Map query] "*OPTIONAL*.")

  (column->base-type ^clojure.lang.Keyword [this, ^Keyword column-type]
    "Given a native DB column type, return the corresponding `Field` `base-type`.")

  (column->special-type ^clojure.lang.Keyword [this, ^String column-name, ^Keyword column-type]
    "*OPTIONAL*. Attempt to determine the special-type of a field given the column name and native type.
     For example, the Postgres driver can mark Postgres JSON type columns as `:json` special type.")

  (connection-details->spec [this, ^Map details-map]
    "Given a `Database` DETAILS-MAP, return a JDBC connection spec.")

  (current-datetime-fn [this]
    "*OPTIONAL*. Korma form that should be used to get the current `DATETIME` (or equivalent). Defaults to `:%now`.")

  (date [this, ^Keyword unit, field-or-value]
    "Return a korma form for truncating a date or timestamp field or value to a given resolution, or extracting a date component.")

  (excluded-schemas ^java.util.Set [this]
    "*OPTIONAL*. Set of string names of schemas to skip syncing tables from.")

  (field-percent-urls [this field]
    "*OPTIONAL*. Implementation of the `:field-percent-urls-fn` to be passed to `make-analyze-table`.
     The default implementation is `fast-field-percent-urls`, which avoids a full table scan. Substitue this with `slow-field-percent-urls` for databases
     where this doesn't work, such as SQL Server")

  (field->alias ^String [this, ^Field field]
    "*OPTIONAL*. Return the alias that should be used to for FIELD, i.e. in an `AS` clause. The default implementation calls `name`, which
     returns the *unqualified* name of `Field`.

     Return `nil` to prevent FIELD from being aliased.")

  (prepare-value [this, ^Value value]
    "*OPTIONAL*. Prepare a value (e.g. a `String` or `Integer`) that will be used in a korma form. By default, this returns VALUE's `:value` as-is, which
     is eventually passed as a parameter in a prepared statement. Drivers such as BigQuery that don't support prepared statements can skip this
     behavior by returning a korma `raw` form instead, or other drivers can perform custom type conversion as appropriate.")

  (quote-style ^clojure.lang.Keyword [this]
    "*OPTIONAL*. Return the quoting style that should be used by [HoneySQL](https://github.com/jkk/honeysql) when building a SQL statement.
      Defaults to `:ansi`, but other valid options are `:mysql`, `:sqlserver`, `:oracle`, and `:h2` (added in `metabase.util.honeysql-extensions`;
      like `:ansi`, but uppercases the result).

        (hsql/format ... :quoting (quote-style driver))")

  (set-timezone-sql ^String [this]
    "*OPTIONAL*. This should be a prepared JDBC SQL statement string to be used to set the timezone for the current transaction.

       \"SET @@session.timezone = ?;\"")

  (stddev-fn ^clojure.lang.Keyword [this]
    "*OPTIONAL*. Keyword name of the SQL function that should be used to do a standard deviation aggregation. Defaults to `:STDDEV`.")

  (string-length-fn ^clojure.lang.Keyword [this, ^Keyword field-key]
    "Return a HoneySQL form appropriate for getting the length of a `Field` identified by fully-qualified FIELD-KEY.
     An implementation should return something like:

      (hsql/call :length (hx/cast :VARCHAR field-key))")

  (unix-timestamp->timestamp [this, field-or-value, ^Keyword seconds-or-milliseconds]
    "Return a korma form appropriate for converting a Unix timestamp integer field or value to an proper SQL `Timestamp`.
     SECONDS-OR-MILLISECONDS refers to the resolution of the int in question and with be either `:seconds` or `:milliseconds`."))


;; This does something important for the Crate driver, apparently (what?)
(extend-protocol jdbc/IResultSetReadColumn
  (class (object-array []))
  (result-set-read-column [x _ _] (PersistentVector/adopt x)))


(def ^:dynamic ^:private connection-pools
  "A map of our currently open connection pools, keyed by DATABASE `:id`."
  (atom {}))

(defn- create-connection-pool
  "Create a new C3P0 `ComboPooledDataSource` for connecting to the given DATABASE."
  [{:keys [id engine details]}]
  (log/debug (u/format-color 'magenta "Creating new connection pool for database %d ..." id))
  (let [spec (connection-details->spec (driver/engine->driver engine) details)]
    (kdb/connection-pool (assoc spec :minimum-pool-size           1
                                     ;; prevent broken connections closed by dbs by testing them every 3 mins
                                     :idle-connection-test-period (* 3 60)
                                     ;; prevent overly large pools by condensing them when connections are idle for 15m+
                                     :excess-timeout              (* 15 60)))))

(defn- notify-database-updated
  "We are being informed that a DATABASE has been updated, so lets shut down the connection pool (if it exists) under
   the assumption that the connection details have changed."
  [_ {:keys [id]}]
  (when-let [pool (get @connection-pools id)]
    (log/debug (u/format-color 'red "Closing connection pool for database %d ..." id))
    ;; remove the cached reference to the pool so we don't try to use it anymore
    (swap! connection-pools dissoc id)
    ;; now actively shut down the pool so that any open connections are closed
    (.close ^ComboPooledDataSource (:datasource pool))))

(defn db->pooled-connection-spec
  "Return a JDBC connection spec that includes a cp30 `ComboPooledDataSource`.
   Theses connection pools are cached so we don't create multiple ones to the same DB."
  [{:keys [id], :as database}]
  (if (contains? @connection-pools id)
    ;; we have an existing pool for this database, so use it
    (get @connection-pools id)
    ;; create a new pool and add it to our cache, then return it
    (u/prog1 (create-connection-pool database)
      (swap! connection-pools assoc id <>))))

(defn db->jdbc-connection-spec
  "Return a JDBC connection spec for DATABASE. Normally this will have a C3P0 pool as its datasource, unless the database is `short-lived`."
  ;; TODO - I don't think short-lived? key is really needed anymore. It's only used by unit tests, and its original purpose was for creating temporary DBs;
  ;; since we don't destroy databases at the end of each test anymore, it's probably time to remove this
  [{:keys [engine details], :as database}]
  (if (:short-lived? details)
    ;; short-lived connections are not pooled, so just return a non-pooled spec
    (connection-details->spec (driver/engine->driver engine) details)
    ;; default behavior is to use a pooled connection
    (db->pooled-connection-spec database)))


(defn escape-field-name
  "Escape dots in a field name so Korma doesn't get confused and separate them. Returns a keyword."
  ^clojure.lang.Keyword [k]
  (keyword (hx/escape-dots (name k))))


(defn- can-connect? [driver details]
  (let [connection (connection-details->spec driver details)]
    (= 1 (first (vals (first (jdbc/query connection ["SELECT 1"])))))))

(defn pattern-based-column->base-type
  "Return a `column->base-type` function that matches types based on a sequence of pattern / base-type pairs."
  [pattern->type]
  (fn [_ column-type]
    (let [column-type (name column-type)]
      (loop [[[pattern base-type] & more] pattern->type]
        (cond
          (re-find pattern column-type) base-type
          (seq more)                    (recur more))))))


(defn honeysql-form->sql+args
  "Convert HONEYSQL-FORM to a vector of SQL string and params, like you'd pass to JDBC."
  [driver honeysql-form]
  {:pre [(map? honeysql-form)]}
  (try (binding [hformat/*subquery?* false]
         (hsql/format honeysql-form
           :quoting             (quote-style driver)
           :allow-dashed-names? true))
       (catch Throwable e
         (log/error (u/format-color 'red "Invalid HoneySQL form:\n%s" (u/pprint-to-str honeysql-form)))
         (throw e))))

(defn- qualify+escape ^clojure.lang.Keyword
  ([table]
   (hx/qualify-and-escape-dots (:schema table) (:name table)))
  ([table field]
   (hx/qualify-and-escape-dots (:schema table) (:name table) (:name field))))

(defn- query
  "Execute a HONEYSQL-FROM query against DATABASE, DRIVER, and optionally TABLE."
  ([driver database honeysql-form]
   (jdbc/query (db->jdbc-connection-spec database)
               (honeysql-form->sql+args driver honeysql-form)))
  ([driver database table honeysql-form]
   (query driver database (merge {:from [(qualify+escape table)]}
                                 honeysql-form))))


(defn- field-values-lazy-seq [driver field]
  (let [table          (field/table field)
        db             (table/database table)
        field-k        (qualify+escape table field)
        pk-field       (field/Field (table/pk-field-id table))
        pk-field-k     (when pk-field
                         (qualify+escape table pk-field))
        transform-fn   (if (contains? #{:TextField :CharField} (:base_type field))
                         u/jdbc-clob->str
                         identity)
        select*        {:select   [[field-k :field]]                ; if we don't specify an explicit ORDER BY some DBs like Redshift will return them in a (seemingly) random order
                        :order-by [[(or pk-field-k field-k) :asc]]} ; try to order by the table's Primary Key to avoid doing full table scans
        fetch-one-page (fn [page-num]
                         (for [{v :field} (query driver db table (apply-page driver select* {:page {:items driver/field-values-lazy-seq-chunk-size
                                                                                                    :page  (inc page-num)}}))]
                           (transform-fn v)))

        ;; This function returns a chunked lazy seq that will fetch some range of results, e.g. 0 - 500, then concat that chunk of results
        ;; with a recursive call to (lazily) fetch the next chunk of results, until we run out of results or hit the limit.
        fetch-page     (fn -fetch-page [page-num]
                         (lazy-seq
                          (let [results             (fetch-one-page page-num)
                                total-items-fetched (* (inc page-num) driver/field-values-lazy-seq-chunk-size)]
                            (concat results (when (and (seq results)
                                                       (< total-items-fetched driver/max-sync-lazy-seq-results)
                                                       (= (count results) driver/field-values-lazy-seq-chunk-size))
                                              (-fetch-page (inc page-num)))))))]
    (fetch-page 0)))


(defn- table-rows-seq [driver database table]
  (query driver database table {:select [:*]}))

(defn- field-avg-length
  [driver field]
  (let [table (field/table field)
        db    (table/database table)]
    (or (some-> (query driver db table {:select [[(hsql/call :avg (string-length-fn driver (qualify+escape table field))) :len]]})
                first
                :len
                math/round
                int)
        0)))

(defn- url-percentage [url-count total-count]
  (if (and total-count (> total-count 0) url-count)
    (float (/ url-count total-count))
    0.0))

;; TODO - Full table scan!?! Maybe just fetch first N non-nil values and do in Clojure-land instead
(defn slow-field-percent-urls
  "Slow implementation of `field-percent-urls` that (probably) requires a full table scan.
   Only use this for DBs where `fast-field-percent-urls` doesn't work correctly, like SQLServer."
  [driver field]
  (let [table       (field/table field)
        db          (table/database table)
        field-k     (qualify+escape table field)
        total-count (:count (first (query driver db table {:select [[:%count.* :count]]
                                                           :where  [:not= field-k nil]})))
        url-count   (:count (first (query driver db table {:select [[:%count.* :count]]
                                                           :where  [:like field-k (hx/literal "http%://_%.__%")]})))]
    (url-percentage url-count total-count)))


(defn fast-field-percent-urls
  "Fast, default implementation of `field-percent-urls` that avoids a full table scan."
  [driver field]
  (let [table       (field/table field)
        db          (table/database table)
        field-k     (qualify+escape table field)
        pk-field    (field/Field (table/pk-field-id table))
        results     (map :is_url (query driver db table (merge {:select [[(hsql/call :like field-k (hx/literal "http%://_%.__%")) :is_url]]
                                                                :where  [:not= field-k nil]
                                                                :limit  driver/max-sync-lazy-seq-results}
                                                               (when pk-field
                                                                 {:order-by [[(qualify+escape table pk-field) :asc]]}))))
        total-count (count results)
        url-count   (count (filter #(or (true? %) (= % 1)) results))]
    (url-percentage url-count total-count)))


(defn features
  "Default implementation of `IDriver` `features` for SQL drivers."
  [driver]
  (cond-> #{:standard-deviation-aggregations
            :foreign-keys
            :expressions
            :native-parameters}
    (set-timezone-sql driver) (conj :set-timezone)))


;;; ## Database introspection methods used by sync process

(defmacro with-metadata
  "Execute BODY with `java.sql.DatabaseMetaData` for DATABASE."
  [[binding _ database] & body]
  `(with-open [^java.sql.Connection conn# (jdbc/get-connection (db->jdbc-connection-spec ~database))]
     (let [~binding (.getMetaData conn#)]
       ~@body)))

(defn fast-active-tables
  "Default, fast implementation of `ISQLDriver/active-tables` best suited for DBs with lots of system tables (like Oracle).
   Fetch list of schemas, then for each one not in `excluded-schemas`, fetch its Tables, and combine the results.

   This is as much as 15x faster for Databases with lots of system tables than `post-filtered-active-tables` (4 seconds vs 60)."
  [driver, ^DatabaseMetaData metadata]
  (let [all-schemas (set (map :table_schem (jdbc/result-set-seq (.getSchemas metadata))))
        schemas     (set/difference all-schemas (excluded-schemas driver))]
    (set (for [schema     schemas
               table-name (mapv :table_name (jdbc/result-set-seq (.getTables metadata nil schema "%" (into-array String ["TABLE", "VIEW"]))))] ; tablePattern "%" = match all tables
           {:name   table-name
            :schema schema}))))

(defn post-filtered-active-tables
  "Alternative implementation of `ISQLDriver/active-tables` best suited for DBs with little or no support for schemas.
   Fetch *all* Tables, then filter out ones whose schema is in `excluded-schemas` Clojure-side."
  [driver, ^DatabaseMetaData metadata]
  (set (for [table (filter #(not (contains? (excluded-schemas driver) (:table_schem %)))
                           (jdbc/result-set-seq (.getTables metadata nil nil "%" (into-array String ["TABLE", "VIEW"]))))] ; tablePattern "%" = match all tables
         {:name   (:table_name table)
          :schema (:table_schem table)})))

(defn- describe-table-fields
  [^DatabaseMetaData metadata, driver, {:keys [schema name]}]
  (set (for [{:keys [column_name type_name]} (jdbc/result-set-seq (.getColumns metadata nil schema name nil))
             :let [calculated-special-type (column->special-type driver column_name (keyword type_name))]]
         (merge {:name      column_name
                 :custom    {:column-type type_name}
                 :base-type (or (column->base-type driver (keyword type_name))
                                (do (log/warn (format "Don't know how to map column type '%s' to a Field base_type, falling back to :UnknownField." type_name))
                                    :UnknownField))}
                (when calculated-special-type
                  {:special-type calculated-special-type})))))

(defn- add-table-pks
  [^DatabaseMetaData metadata, table]
  (let [pks (->> (.getPrimaryKeys metadata nil nil (:name table))
                 jdbc/result-set-seq
                 (mapv :column_name)
                 set)]
    (update table :fields (fn [fields]
                            (set (for [field fields]
                                   (if-not (contains? pks (:name field))
                                     field
                                     (assoc field :pk? true))))))))

(defn describe-database
  "Default implementation of `describe-database` for JDBC-based drivers."
  [driver database]
  (with-metadata [metadata driver database]
    {:tables (active-tables driver, ^DatabaseMetaData metadata)}))

(defn- describe-table [driver database table]
  (with-metadata [metadata driver database]
    (->> (assoc (select-keys table [:name :schema]) :fields (describe-table-fields metadata driver table))
         ;; find PKs and mark them
         (add-table-pks metadata))))

(defn- describe-table-fks [driver database table]
  (with-metadata [metadata driver database]
    (set (->> (.getImportedKeys metadata nil (:schema table) (:name table))
              jdbc/result-set-seq
              (mapv (fn [result]
                      {:fk-column-name   (:fkcolumn_name result)
                       :dest-table       {:name   (:pktable_name result)
                                          :schema (:pktable_schem result)}
                       :dest-column-name (:pkcolumn_name result)}))))))


(defn analyze-table
  "Default implementation of `analyze-table` for SQL drivers."
  [driver table new-table-ids]
  ((analyze/make-analyze-table driver
     :field-avg-length-fn   (partial field-avg-length driver)
     :field-percent-urls-fn (partial field-percent-urls driver))
   driver
   table
   new-table-ids))


(defn ISQLDriverDefaultsMixin
  "Default implementations for methods in `ISQLDriver`."
  []
  (require 'metabase.driver.generic-sql.query-processor)
  {:active-tables        fast-active-tables
   :apply-aggregation    (resolve 'metabase.driver.generic-sql.query-processor/apply-aggregation) ; don't resolve the vars yet so during interactive dev if the
   :apply-breakout       (resolve 'metabase.driver.generic-sql.query-processor/apply-breakout) ; underlying impl changes we won't have to reload all the drivers
   :apply-fields         (resolve 'metabase.driver.generic-sql.query-processor/apply-fields)
   :apply-filter         (resolve 'metabase.driver.generic-sql.query-processor/apply-filter)
   :apply-join-tables    (resolve 'metabase.driver.generic-sql.query-processor/apply-join-tables)
   :apply-limit          (resolve 'metabase.driver.generic-sql.query-processor/apply-limit)
   :apply-order-by       (resolve 'metabase.driver.generic-sql.query-processor/apply-order-by)
   :apply-page           (resolve 'metabase.driver.generic-sql.query-processor/apply-page)
   :column->special-type (constantly nil)
   :current-datetime-fn  (constantly :%now)
   :excluded-schemas     (constantly nil)
   :field->alias         (u/drop-first-arg name)
   :field-percent-urls   fast-field-percent-urls
   :prepare-value        (u/drop-first-arg :value)
   :quote-style          (constantly :ansi)
   :set-timezone-sql     (constantly nil)
   :stddev-fn            (constantly :STDDEV)})


(defn IDriverSQLDefaultsMixin
  "Default implementations of methods in `IDriver` for SQL drivers."
  []
  (require 'metabase.driver.generic-sql.query-processor)
  (merge driver/IDriverDefaultsMixin
         {:analyze-table           analyze-table
          :can-connect?            can-connect?
          :describe-database       describe-database
          :describe-table          describe-table
          :describe-table-fks      describe-table-fks
          :execute-query           (resolve 'metabase.driver.generic-sql.query-processor/execute-query)
          :features                features
          :field-values-lazy-seq   field-values-lazy-seq
          :mbql->native            (resolve 'metabase.driver.generic-sql.query-processor/mbql->native)
          :notify-database-updated notify-database-updated
          :table-rows-seq          table-rows-seq}))



;;; ### Util Fns

(defn create-db
  "Like `korma.db/create-db`, but adds a fn to unescape escaped dots when generating SQL."
  [spec]
  (update-in (kdb/create-db spec) [:options :naming :fields] comp hx/unescape-dots))


(defn- db->korma-db
  "Return a Korma DB spec for Metabase DATABASE."
  [{:keys [details engine], :as database}]
  (let [spec (connection-details->spec (driver/engine->driver engine) details)]
    (assoc (create-db spec)
      :pool (db->jdbc-connection-spec database))))

(defn create-entity
  "Like `korma.db/create-entity`, but takes a sequence of name components instead; escapes dots in names as well."
  [name-components]
  (k/create-entity (apply str (interpose "." (for [s     name-components
                                                   :when (seq s)]
                                               (name (hx/escape-dots (name s))))))))

(defn korma-entity
  "Return a Korma entity for [DB and] TABLE.

    (-> (Table :id 100)
        korma-entity
        (select (aggregate (count :*) :count)))"
  ([table]    (korma-entity (table/database table) table))
  ([db table] (let [{schema :schema, table-name :name} table]
                (k/database
                  (create-entity [schema table-name])
                  (db->korma-db db)))))
