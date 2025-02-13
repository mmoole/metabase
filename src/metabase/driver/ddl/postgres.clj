(ns metabase.driver.ddl.postgres
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql.util :as sql.u]
            [metabase.public-settings :as public-settings]
            [metabase.query-processor :as qp]
            [metabase.util.i18n :refer [trs]]))

(defn- quote-fn [driver]
  (fn quote [ident entity]
    (sql.u/quote-name driver ident (ddl.i/format-name driver entity))))

(defn- create-schema-sql
  "SQL string to create a schema suitable for postgres"
  [{driver :engine :as database}]
  (let [q (quote-fn driver)]
    (format "create schema %s"
            (q :table (ddl.i/schema-name database (public-settings/site-uuid))))))

(defn- create-table-sql [{driver :engine :as database} definition]
  (let [q (quote-fn driver)]
    (format "create table %s.%s (%s);"
            (q :table (ddl.i/schema-name database (public-settings/site-uuid)))
            (q :table (:table-name definition))
            (str/join
             ", "
             (for [{:keys [field-name base-type]} (:field-definitions definition)]
               (format "%s %s"
                       (q :field field-name)
                       (ddl.i/field-base-type->sql-type driver base-type)))))))

(defn- drop-table-sql [{driver :engine :as database} table-name]
  (let [q (quote-fn driver)]
    (format "drop table if exists %s.%s"
            (q :table (ddl.i/schema-name database (public-settings/site-uuid)))
            (q :table table-name))))

(defn- populate-table-sql [{driver :engine :as database} definition query]
  (let [q (quote-fn driver)]
   (format "insert into %s.%s (%s) %s"
           (q :table (ddl.i/schema-name database (public-settings/site-uuid)))
           (q :table (:table-name definition))
           (str/join
            ", "
            (for [{:keys [field-name]} (:field-definitions definition)]
              (q :field field-name)))
           query)))

(defmethod ddl.i/refresh! :postgres [_driver database definition dataset-query]
  (try
    (jdbc/with-db-connection [conn (sql-jdbc.conn/db->pooled-connection-spec database)]
      (jdbc/execute! conn [(drop-table-sql database (:table-name definition))])
      (jdbc/execute! conn [(create-table-sql database definition)])
      (jdbc/execute! conn [(populate-table-sql database definition (-> dataset-query
                                                                       qp/compile
                                                                       :query))])
      {:state :success})
    (catch Exception e
      {:state :error :error (ex-message e)})))

(defmethod ddl.i/unpersist! :postgres
  [_driver database persisted-info]
  (jdbc/with-db-connection [conn (sql-jdbc.conn/db->pooled-connection-spec database)]
    (try
      (jdbc/execute! conn [(drop-table-sql database (:table_name persisted-info))])
      (catch Exception e
        (log/warn e)
        (throw e)))))

(defmethod ddl.i/check-can-persist :postgres
  [database]
  (let [schema-name (ddl.i/schema-name database (public-settings/site-uuid))
        table-name  (format "persistence_check_%s" (rand-int 10000))
        steps       [[:persist.check/create-schema
                      (fn check-schema [conn]
                        (let [existing-schemas (into #{} (map :schema_name)
                                                     (jdbc/query conn
                                                                 ["select schema_name from information_schema.schemata"]))]
                          (or (contains? existing-schemas schema-name)
                              (jdbc/execute! conn [(create-schema-sql database)]))))]
                     [:persist.check/create-table
                      (fn create-table [conn]
                        (jdbc/execute! conn
                                       (create-table-sql database
                                                         {:table-name table-name
                                                          :field-definitions [{:field-name "field"
                                                                               :base-type :type/Text}]})))]
                     [:persist.check/read-table
                      (fn read-table [conn]
                        (jdbc/query conn [(format "select * from %s.%s"
                                                  schema-name table-name)]))]
                     [:persist.check/delete-table
                      (fn delete-table [conn]
                        (jdbc/execute! conn [(drop-table-sql database table-name)]))]]]
    (jdbc/with-db-connection [conn (sql-jdbc.conn/db->pooled-connection-spec database)]
      (loop [[[step stepfn] & remaining] steps]
        (let [result (try (stepfn conn)
                          (log/info (trs "Step {0} was successful for db {1}"
                                         step (:name database)))
                          ::valid
                          (catch Exception e
                            (log/warn (trs "Error in `{0}` while checking for model persistence permissions." step))
                            (log/warn e)
                            step))]
          (cond (and (= result ::valid) remaining)
                (recur remaining)

                (= result ::valid)
                [true :persist.check/valid]

                :else [false step]))))))
