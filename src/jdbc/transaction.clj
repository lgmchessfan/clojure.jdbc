;; Copyright 2014 Andrey Antukh <niwi@niwi.be>
;;
;; Licensed under the Apache License, Version 2.0 (the "License")
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns jdbc.transaction
  "Transactions support for clojure.jdbc"
  (:require [jdbc.constants :as constants]
            [jdbc.proto :as proto])
  (:import java.sql.Connection))

(defprotocol ITransactionStrategy
  (begin! [_ conn opts] "Starts a transaction and return a connection instance.")
  (rollback! [_ conn opts] "Rollbacks a transaction. Returns nil.")
  (commit! [_ conn opts] "Commits a transaction. Returns nil."))

(def ^{:doc "Default transaction strategy implementation."
       :dynamic true}
  *default-tx-strategy*
  (reify ITransactionStrategy
    (begin! [_ conn opts]
      (let [^Connection rconn (proto/get-connection conn)
            metadata (-> (meta conn)
                         (assoc :rollback (atom false)
                                :prev-isolation (.getTransactionIsolation rconn)
                                :prev-readonly (.isReadOnly rconn)))]
        (if (:transaction metadata)
          (let [sp (.setSavepoint rconn)]
            (with-meta conn
              (assoc metadata :savepoint sp :transaction true)))

          (let [prev-autocommit (.getAutoCommit rconn)]
            (.setAutoCommit rconn false)
            (when-let [isolation (:isolation-level opts)]
              (.setTransactionIsolation rconn (get constants/isolation-levels isolation)))
            (when-let [read-only (:read-only opts)]
              (.setReadOnly rconn read-only))
            (with-meta conn
              (assoc metadata :prev-autocommit prev-autocommit :transaction true))))))

    (rollback! [_ conn opts]
      (let [^Connection rconn (proto/get-connection conn)
            metadata (meta conn)]
        (if-let [savepoint (:savepoint metadata)]
          (.rollback rconn savepoint)
          (do
            (.rollback rconn)
            (.setAutoCommit rconn (:prev-autocommit metadata))
            (.setTransactionIsolation rconn (:prev-isolation metadata))
            (.setReadOnly rconn (:prev-readonly metadata))))))

    (commit! [ts conn opts]
      (let [^Connection rconn (proto/get-connection conn)
            metadata  (meta conn)]
        ;; In case on commit and rollback flag is set, commit action
        ;; should be ignored and rollback will performed.
        (if @(:rollback metadata)
          (rollback! ts conn opts)
          (if-let [savepoint (:savepoint metadata)]
            (.releaseSavepoint rconn savepoint)
            (do
              (.commit rconn)

              (.setAutoCommit rconn (:prev-autocommit metadata))
              (.setTransactionIsolation rconn (:prev-isolation metadata))
              (.setReadOnly rconn (:prev-readonly metadata)))))))))


(defn wrap-transaction-strategy
  "Simple helper function that associate a strategy
  to a connection and return a new connection object
  with wrapped stragy.

  Example:

  (let [conn (wrap-transaction-strategy simplecon (MyStrategy.))]
    (use-your-new-conn conn))
  "
  [conn strategy]
  (let [metadata (meta conn)]
    (with-meta conn (assoc metadata :transaction-strategy strategy))))

(defn set-rollback!
  "Mark a current connection for rollback.

  It ensures that on the end of the current transaction
  instead of commit changes, rollback them.

  This function should be used inside of a transaction
  block, otherwise this function does nothing.

  Example:

  (with-transaction conn
    (make-some-queries-without-changes conn)
    (set-rollback! conn))
  "
  [conn]
  (let [metadata (meta conn)]
    (when-let [rollback-flag (:rollback metadata)]
      (reset! rollback-flag true))))

(defn unset-rollback!
  "Revert flag setted by `set-rollback!` function.
  This function should be used inside of a transaction
  block, otherwise this function does nothing."
  [conn]
  (let [metadata (meta conn)]
    (when-let [rollback-flag (:rollback metadata)]
      (reset! rollback-flag false))))

(defn is-rollback-set?
  "Check if a current connection in one transaction
  is marked for rollback.

  This should be used in one transaction, in other case this
  function always return false."
  [conn]
  (let [metadata (meta conn)]
    (if-let [rollback-flag (:rollback metadata)]
      (deref rollback-flag)
      false)))

(defn call-in-transaction
  "Wrap function in one transaction.
  This function accepts as a parameter a transaction strategy. If no one
  is specified, `DefaultTransactionStrategy` is used.

  With `DefaultTransactionStrategy`, if current connection is already in
  transaction, it uses truly nested transactions for properly handle it.
  The availability of this feature depends on database support for it.

  Example:

  (with-connection dbspec conn
    (call-in-transaction conn (fn [conn] (execute! conn 'DROP TABLE foo;'))))

  For more idiomatic code, you should use `with-transaction` macro.

  Depending on transaction strategy you are using, this function can accept
  additional parameters. The default transaction strategy exposes two additional
  parameters:

  - `:isolation-level` - set isolation level for this transaction
  - `:read-only` - set current transaction to read only
  "
  [conn func & [{:keys [savepoints strategy] :or {savepoints true} :as opts}]]
  (let [metadata (meta conn)
        tx-strategy (or strategy
                        (:transaction-strategy metadata)
                        *default-tx-strategy*)]
    (when (and (:transaction metadata) (not savepoints))
      (throw (RuntimeException. "Savepoints explicitly disabled.")))

    (let [conn (begin! tx-strategy conn opts)
          metadata (meta conn)]
      (try
        (let [returnvalue (func conn)]
          (commit! tx-strategy conn opts)
          returnvalue)
        (catch Throwable t
          (rollback! tx-strategy conn opts)
          (throw t))))))

(defmacro with-transaction-strategy
  "Set some transaction strategy connection in the current context scope.
  This method not uses thread-local dynamic variables and connection
  preserves a transaction strategy throught threads."
  [conn strategy & body]
  `(let [~conn (wrap-transaction-strategy ~conn ~strategy)]
     ~@body))

(defmacro with-transaction
  "Creates a context that evaluates in transaction (or nested transaction).
  This is a more idiomatic way to execute some database operations in
  atomic way.

  Example:

  (with-transaction conn
    (execute! conn 'DROP TABLE foo;')
    (execute! conn 'DROP TABLE bar;'))

  Also, you can pass additional options to transaction:

  (with-transaction conn {:read-only true}
    (execute! conn 'DROP TABLE foo;')
    (execute! conn 'DROP TABLE bar;'))
  "
  [conn & body]
  (if (map? (first body))
    `(let [func# (fn [c#] (let [~conn c#] ~@(next body)))]
       (call-in-transaction ~conn func# ~(first body)))
    `(let [func# (fn [c#] (let [~conn c#] ~@body))]
       (call-in-transaction ~conn func#))))
