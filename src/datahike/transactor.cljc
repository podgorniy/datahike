(ns datahike.transactor
  (:require [datahike.core :as d]
            [superv.async :refer [go-try- <?- S go-loop-super]]
            [taoensso.timbre :as log]
            [clojure.core.async :refer [>! chan close! promise-chan put! go]]))

(defprotocol PTransactor
  ; Send a transaction. Returns a channel that resolves when the transaction finalizes.
  (send-transaction! [_ tx-data tx-fn])
  ; Returns a channel that resolves when the transactor has shut down.
  (shutdown [_]))

(defrecord LocalTransactor
           [rx-queue rx-thread]
  PTransactor
  (send-transaction! [_ tx-data tx-fn]
    (go-try-
      (let [p (promise-chan)]
        (>! rx-queue {:tx-data tx-data :callback p :tx-fn tx-fn})
        (<?- p))))

  (shutdown [_]
    (close! rx-queue)
    rx-thread))

(defn create-rx-thread
  [connection rx-queue update-and-flush-db]
  (let [resolve-fn (memoize resolve)]
    (go-loop-super S []
      (if-let [{:keys [tx-data callback tx-fn]} (<?- rx-queue)]
        (do
          (let [update-fn (resolve-fn tx-fn)
                tx-report (try (update-and-flush-db connection tx-data update-fn)
                                 ; Only catch ExceptionInfo here (intentionally rejected transactions).
                                 ; Any other exceptions should crash the transactor and signal the supervisor.
                               (catch Exception e
                                 (log/trace "Exception in transactor: " e)
                                 e))]
            (when (some? callback)
              (put! callback tx-report)))
          (recur))
        (do
          (log/debug "Transactor rx thread gracefully closed"))))))

(defmulti create-transactor
  (fn [transactor-config conn update-and-flush-db]
    (or (:backend transactor-config) :local)))

(defmethod create-transactor :local
  [{:keys [rx-buffer-size]} connection update-and-flush-db]
  (let [rx-queue (chan rx-buffer-size)
        rx-thread (create-rx-thread connection rx-queue update-and-flush-db)]
    (map->LocalTransactor
     {:rx-queue  rx-queue
      :rx-thread rx-thread})))
