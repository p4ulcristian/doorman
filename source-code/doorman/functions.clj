(ns doorman.functions
  "The SQL functions doorman makes from the app's sentences and denies,
   each named by a hash of what it reads, so a changed sentence is a new
   function. Each is plpgsql (its body is read when it runs, so it can be
   made before the tables it reads) and STABLE (a statement asks it once).

   - doorman.via_…(): a :via's related rows, the values they offer for
     what it matches (via_1 …), for the one asking.
   - doorman.denied_…(): the ids of a kind's scopes in a denied state.
   - doorman.unchanged_…(id, columns…, whens…): whether a row of a table a
     :via reads is unchanged in what the via matches (and no via's :when
     holds that didn't), compared with the row as it was before the
     statement.
   - doorman.\"may <action> on <table>\"(): the ids of a table's rows the
     asker may do an action on; it runs as the caller, behind the gates.
   - doorman.fill_…(): a record under a record, its carried columns set
     from its parent's row before an insert or a change of its parent
     (doorman.parents).

   The first three read past the gates: doorman.passing is 'on' only while
   they read, then back to what it was; an error ends the transaction, and
   the setting with it. Postgres doesn't loop on a related table's own
   gates, and they answer by the sentences, not by what the asker may read."
  (:require
   [clojure.string :as str]
   [cellar.api :as cellar]
   [doorman.conditions :as conditions]
   [doorman.parents :as parents]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.sentences :as sentences]))

(defn- passing-body
  "A plpgsql body that reads past the gates while it runs `statements`."
  [declarations statements]
  (str "DECLARE before text DEFAULT coalesce(current_setting('doorman.passing', true), '');\n"
       declarations
       "BEGIN\n"
       "  PERFORM set_config('doorman.passing', 'on', true);\n"
       statements
       "  PERFORM set_config('doorman.passing', before, true);\n"))

(defn make-via-function-statement
  "The statement that makes a via's function."
  [{:keys [table to] condition :when :as via} {:keys [tables-by-key] :as place}]
  (let [other  (get tables-by-key table)
        pairs  (sentences/find-matched-pairs via)
        outs   (map-indexed (fn [i [_ theirs]]
                              (str "via_" (inc i) " " (sentences/turn-type-into-postgres-type (sentences/find-column-type other theirs))))
                            pairs)
        who    (sentences/turn-who-into-sql to nil other place)]
    (str "CREATE OR REPLACE FUNCTION " (sentences/find-via-function-name via tables-by-key) "()\n"
         "RETURNS TABLE (" (str/join ", " outs) ") LANGUAGE plpgsql STABLE AS $$\n"
         (passing-body ""
                       (str "  RETURN QUERY SELECT DISTINCT " (str/join ", " (map (comp sentences/column-name second) pairs))
                            " FROM " (cellar/turn-table-key-into-table-name table)
                            " WHERE " who (when condition (str " AND " (sentences/turn-when-into-sql condition))) ";\n"))
         "END $$")))

(defn make-denied-function-statement
  "The statement that makes a scope-state deny's function."
  [{:keys [kind] condition :when :as deny} {:keys [tables-by-key scope-kinds]}]
  (let [table-key (get-in scope-kinds [kind :table])
        table     (get tables-by-key table-key)
        id        (first (sentences/find-primary-key-columns table))]
    (str "CREATE OR REPLACE FUNCTION " (conditions/find-denied-function-name deny) "()\n"
         "RETURNS SETOF uuid LANGUAGE plpgsql STABLE AS $$\n"
         (passing-body ""
                       (str "  RETURN QUERY SELECT " (sentences/column-name id) " FROM "
                            (cellar/turn-table-key-into-table-name table-key)
                            " WHERE " (sentences/turn-when-into-sql condition) ";\n"))
         "END $$")))

(defn make-unchanged-function-statement
  "The statement that makes the guard's function for a table a :via reads."
  [table {:keys [columns whens] :as guard}]
  (let [table-key (:table table)
        id        (first (sentences/find-primary-key-columns table))
        type-of   #(sentences/turn-type-into-postgres-type (sentences/find-column-type table %))
        params    (concat [(str "row_id " (type-of id))]
                          (map-indexed (fn [i c] (str "column_" (inc i) " " (type-of c))) columns)
                          (map-indexed (fn [i _] (str "when_" (inc i) " boolean")) whens))
        same      (concat [(str (sentences/column-name id) " = row_id")]
                          (map-indexed (fn [i c] (str (sentences/column-name c) " IS NOT DISTINCT FROM column_" (inc i))) columns)
                          (map-indexed (fn [i w] (str "(NOT when_" (inc i) " OR (" (sentences/turn-when-into-sql w) ") IS TRUE)")) whens))]
    (str "CREATE OR REPLACE FUNCTION " (conditions/find-unchanged-function-name table-key guard) "(" (str/join ", " params) ")\n"
         "RETURNS boolean LANGUAGE plpgsql STABLE AS $$\n"
         (passing-body "        found_same boolean;\n"
                       (str "  SELECT EXISTS (SELECT 1 FROM " (cellar/turn-table-key-into-table-name table-key)
                            " WHERE " (str/join " AND " same) ") INTO found_same;\n"))
         "  RETURN found_same;\n"
         "END $$")))

(defn make-may-function-statement
  "The statement that makes the function narrowing a table's rows to those
   the asker may do an action on."
  [table action action-condition]
  (let [id (first (sentences/find-primary-key-columns table))]
    (str "CREATE OR REPLACE FUNCTION " (conditions/find-may-function-name action (:table table)) "()\n"
         "RETURNS SETOF " (sentences/turn-type-into-postgres-type (sentences/find-column-type table id))
         " LANGUAGE plpgsql STABLE AS $$\n"
         "BEGIN\n"
         "  RETURN QUERY SELECT " (sentences/column-name id) " FROM " (cellar/turn-table-key-into-table-name (:table table))
         " WHERE " action-condition ";\n"
         "END $$")))

(defn make-function-statements
  "Every statement making the functions the app's sentences and denies
   need. The sentences are checked first."
  [tables scope-kinds]
  (let [conditions    (conditions/make-sql-conditions tables scope-kinds)
        tables-by-key (into {} (map (juxt :table identity)) tables)
        place         {:tables-by-key tables-by-key :scope-kinds scope-kinds}
        vias          (distinct (for [{:keys [access]} tables :when (sequential? access)
                                      {:keys [via]} access :when via]
                                  via))]
    (vec (concat
          (map #(make-via-function-statement % place) vias)
          (for [deny (scope-kinds/find-denies scope-kinds) :when (:when deny)]
            (make-denied-function-statement deny place))
          (for [table tables
                :let [guard (conditions/find-guard (:table table) tables)]
                :when (and guard (= 1 (count (sentences/find-primary-key-columns table))))]
            (make-unchanged-function-statement table guard))
          (for [table tables
                :when (and (sequential? (:access table)) (= 1 (count (sentences/find-primary-key-columns table))))
                [action condition] (sort-by (comp str key) (get-in conditions [(:table table) :actions]))]
            (make-may-function-statement table action condition))
          (for [table tables :when (:parent table)]
            (parents/make-fill-function-statement table tables-by-key))))))
