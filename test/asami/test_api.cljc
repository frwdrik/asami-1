(ns asami.test-api
  "Tests the public query functionality"
  (:require [asami.core :refer [q query-plan create-database connect db transact entity now as-of since]]
            [asami.index :as i]
            [asami.multi-graph :as m]
            [schema.core :as s]
            #?(:clj  [clojure.test :refer [is use-fixtures testing]]
               :cljs [clojure.test :refer-macros [is run-tests use-fixtures testing]])
            #?(:clj  [schema.test :as st :refer [deftest]]
               :cljs [schema.test :as st :refer-macros [deftest]]))
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(deftest test-create
  (let [c1 (create-database "asami:mem://babaco")
        c2 (create-database "asami:mem://babaco")
        c3 (create-database "asami:mem://kumquat")
        c4 (create-database "asami:mem://kumquat")
        c5 (create-database "asami:multi://kumquat")]
    (is c1)
    (is (not c2))
    (is c3)
    (is (not c4))
    (is c5)
    (is (thrown-with-msg? ExceptionInfo #"Local Databases not yet implemented"
                          (create-database "asami:local://kumquat")))))

(deftest test-connect
  (let [c (connect "asami:mem://apple")
        cm (connect "asami:multi://banana")]
    (is (instance? asami.index.GraphIndexed (:graph (:db @(:state c)))))
    (is (= "apple" (:name c)))
    (is (instance? asami.multi_graph.MultiGraph (:graph (:db @(:state cm)))))
    (is (= "banana" (:name cm)))))

(deftest load-data
  (let [c (connect "asami:mem://test1")
        r (transact c {:tx-data [{:db/ident "bobid"
                                  :person/name "Bob"
                                  :person/spouse "aliceid"}
                                 {:db/ident "aliceid"
                                  :person/name "Alice"
                                  :person/spouse "bobid"}]})]
    (= (->> (:tx-data @r)
            (filter #(= :db/ident (second %)))
            (map #(nth % 2))
            set)
       #{"bobid" "aliceid"}))

  (let [c (connect "asami:mem://test2")
        r (transact c {:tx-data [[:db/add :mem/node-1 :property "value"]
                                 [:db/add :mem/node-2 :property "other"]]})]

    (is (= 2 (count (:tx-data @r)))))

  (let [c (connect "asami:mem://test2")
        maksim {:db/id -1
                :name  "Maksim"
                :age   45
                :wife {:db/id -2}
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {:keys [tempids tx-data] :as r} @(transact c [maksim anna])
        one (tempids -1)
        two (tempids -2)]
    (is (= 19 (count tx-data)))
    (is (= #{[one :db/ident one]
             [one :tg/entity true]
             [one :name "Maksim"]
             [one :age 45]
             [one :wife two]}
           (->> tx-data
                (filter #(= one (first %)))
                (remove #(= :aka (second %)))
                (map (partial take 3))
                set)))
    (is (= #{[two :db/ident two]
             [two :tg/entity true]
             [two :name "Anna"]
             [two :age 31]
             [two :husband one]}
           (->> tx-data
                (filter #(= two (first %)))
                (remove #(= :aka (second %)))
                (map (partial take 3))
                set))))

  (let [c (connect "asami:mem://test3")
        maksim {:db/ident "maksim"
                :name  "Maksim"
                :age   45
                :wife {:db/ident "anna"}
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/ident "anna"
                :name  "Anna"
                :age   31
                :husband {:db/ident "maksim"}
                :aka   ["Anitzka"]}
        {:keys [tempids tx-data] :as r} @(transact c [maksim anna])
        one (tempids "maksim")
        two (tempids "anna")]
    (is (= 19 (count tx-data)))
    (is (= #{[one :db/ident "maksim"]
             [one :tg/entity true]
             [one :name "Maksim"]
             [one :age 45]
             [one :wife two]}
           (->> tx-data
                (filter #(= one (first %)))
                (remove #(= :aka (second %)))
                (map (partial take 3))
                set)))
    (is (= #{[two :db/ident "anna"]
             [two :tg/entity true]
             [two :name "Anna"]
             [two :age 31]
             [two :husband one]}
           (->> tx-data
                (filter #(= two (first %)))
                (remove #(= :aka (second %)))
                (map (partial take 3))
                set)))))

(deftest test-entity
  (let [c (connect "asami:mem://test4")
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        pete   {:db/id -3
                :db/ident "pete"
                :name  #{"Peter" "Pete" "Petrov"}
                :age   25
                :aka ["Peter the Great" #{"Petey" "Petie"}]
                }
        {:keys [tempids tx-data] :as r} @(transact c [maksim anna pete])
        one (tempids -1)
        two (tempids -2)
        three (tempids -3)
        d (db c)]
    (is (= {:name  "Maksim"
            :age   45
            :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
           (entity d one)))
    (is (= {:name  "Maksim"
            :age   45
            :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
           (entity d :maksim)))
    (is (= {:name  "Anna"
            :age   31
            :husband {:db/ident :maksim}
            :aka   ["Anitzka"]}
           (entity d :anna)))
    (is (= {:name  #{"Peter" "Pete" "Petrov"}
            :age   25
            :aka ["Peter the Great" ["Petey" "Petie"]]}
           (entity d three)))
    ))

(defn sleep [msec]
  #?(:clj
     (Thread/sleep msec)
     :cljs
     (let [deadline (+ msec (.getTime (js/Date.)))]
       (while (> deadline (.getTime (js/Date.)))))))

(deftest test-as
  (let [t0 (now)
        _ (sleep 100)
        c (connect "asami:mem://test5")
        _ (sleep 100)
        t1 (now)
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/ident :maksim}
                :aka   ["Anitzka"]}
        _ (sleep 100)
        _ @(transact c [maksim])
        _ (sleep 100)
        t2 (now)
        _ (sleep 100)
        _ @(transact c [anna])
        _ (sleep 100)
        t3 (now)
        latest-db (db c)
        db0 (as-of latest-db t0)  ;; before
        db1 (as-of latest-db t1)  ;; empty
        db2 (as-of latest-db t2)  ;; after first tx
        db3 (as-of latest-db t3)  ;; after second tx
        db0' (as-of latest-db 0)  ;; empty
        db1' (as-of latest-db 1)  ;; after first tx
        db2' (as-of latest-db 2)  ;; after second tx
        db3' (as-of latest-db 3)  ;; still after second tx

        db0* (since latest-db t0)  ;; before
        db1* (since latest-db t1)  ;; after first tx
        db2* (since latest-db t2)  ;; after second tx
        db3* (since latest-db t3)  ;; well after second tx
        db0*' (since latest-db 0)  ;; after first tx
        db1*' (since latest-db 1)  ;; after second tx 
        db2*' (since latest-db 2)  ;; still after second tx
        db3*' (since latest-db 3)] ;; still after second tx
    (is (= db0 db0'))
    (is (= db0 db1))
    (is (= db2 db1'))
    (is (= db3 db2'))
    (is (= db2' db3'))
    (is (= db0 db0*))
    (is (= db2 db1*))
    (is (= db3 db2*))
    (is (= nil db3*))
    (is (= db2 db0*'))
    (is (= db3 db1*'))
    (is (= nil db2*'))
    (is (= nil db3*'))
    (is (= db3 latest-db))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db0))
           #{}))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db1))
           #{}))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db2))
           #{["Maksim"]}))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db3))
           #{["Maksim"] ["Anna"]}))))

(deftest test-update
  (let [c (connect "asami:mem://test6")
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {db1 :db-after} @(transact c [maksim anna])
        anne   {:db/ident :anna
                :name' "Anne"
                :age   32}
        {db2 :db-after} @(transact c [anne])
        maks   {:db/ident :maksim
                :aka' ["Maks Otto von Stirlitz"]}
        {db3 :db-after} @(transact c [maks])]
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :tg/contains ?aka]] db1))
           #{["Maks Otto von Stirlitz"] ["Jack Ryan"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db1))
           #{[31]}))
    (is (= (set (q '[:find ?name :where [?e :db/ident :anna] [?e :name ?name]] db1))
           #{["Anna"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db2))
           #{[31] [32]}))
    (is (= (set (q '[:find ?name :where [?e :db/ident :anna] [?e :name ?name]] db2))
           #{["Anne"]}))
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :tg/contains ?aka]] db3))
           #{["Maks Otto von Stirlitz"]}))))

(deftest test-append
  (let [c (connect "asami:mem://test7")
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka" "Annie"]}
        {db1 :db-after :as tx1} @(transact c [maksim anna])
        anne   {:db/ident :anna
                :aka+ "Anne"
                :age   32
                :friend+ "Peter"}
        {db2 :db-after :as tx2} @(transact c [anne])]
    (is (= (set (q '[:find ?aka :where [?e :name "Anna"] [?e :aka ?a] [?a :tg/contains ?aka]] db1))
           #{["Anitzka"] ["Annie"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db1))
           #{[31]}))
    (is (= {:name "Anna" :age 31 :aka ["Anitzka" "Annie"] :husband {:db/ident :maksim}}
           (entity db1 :anna)))

    (is (= (set (q '[:find ?aka :where [?e :name "Anna"] [?e :aka ?a] [?a :tg/contains ?aka]] db2))
           #{["Anitzka"] ["Annie"] ["Anne"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db2))
           #{[31] [32]}))
    (is (= (set (q '[:find ?friend :where [?e :name "Anna"] [?e :friend ?f] [?f :tg/contains ?friend]] db2))
           #{["Peter"]}))
    (is (= {:name "Anna" :age #{31 32} :aka ["Anitzka" "Annie" "Anne"] :friend ["Peter"] :husband {:db/ident :maksim}}
           (entity db2 :anna)))))

(def transitive-data
    [{:db/id -1 :name "Washington Monument"}
     {:db/id -2 :name "National Mall"}
     {:db/id -3 :name "Washington, DC"}
     {:db/id -4 :name "USA"}
     {:db/id -5 :name "Earth"}
     {:db/id -6 :name "Solar System"}
     {:db/id -7 :name "Orion-Cygnus Arm"}
     {:db/id -8 :name "Milky Way Galaxy"}
     [:db/add -1 :is-in -2]
     [:db/add -2 :is-in -3]
     [:db/add -3 :is-in -4]
     [:db/add -4 :is-in -5]
     [:db/add -5 :is-in -6]
     [:db/add -6 :is-in -7]
     [:db/add -7 :is-in -8]])

(deftest test-transitive
  (let [c (connect "asami:mem://test8")
        tx (transact c {:tx-data transitive-data})
        d (db c)]
    (is (=
         (q '{:find [?name ...]
                :where [[?e :name "Washington Monument"]
                        [?e :is-in ?e2]
                        [?e2 :name ?name]]} d)
         ["National Mall"]))
    ;; How does this work at the repl, and not here?
    #_(is (=
         (q '{:find [?name ...]
                :where [[?e :name "Washington Monument"]
                        [?e :is-in* ?e2]
                        [?e2 :name ?name]]} d)
         ["National Mall" "Washington, DC" "USA" "Earth" "Solar System" "Orion-Cygnus Arm" "Milky Way Galaxy"]))))

;; tests both the query-plan function and the options
(deftest test-plan
  (let [c (connect "asami:mem://test9")
        tx (transact c {:tx-data transitive-data})
        d (db c)
        p1 (query-plan '[:find [?name ...]
                         :where [?e :name "Washington Monument"]
                         [?e :is-in ?e2]
                         [?e2 :name ?name]] d)
        p2 (query-plan '[:find [?name ...]
                         :where [?e2 :name ?name]
                         [?e :is-in ?e2]
                         [?e :name "Washington Monument"]] d)
        p3 (query-plan '[:find [?name ...]
                         :where [?e2 :name ?name]
                         [?e :is-in ?e2]
                         [?e :name "Washington Monument"]] d :planner :user)
        p4 (query-plan '[:find (count ?name)
                         :where [?e2 :name ?name]
                         [?e :is-in ?e2]
                         [?e :name "Washington Monument"]] d)]
    (is (= p1 '{:plan [[?e :name "Washington Monument"]
                       [?e :is-in ?e2]
                       [?e2 :name ?name]]}))
    ;; How does this work at the repl, and not here?
    #_(is (= p2 '{:plan [[?e :name "Washington Monument"]
                       [?e :is-in ?e2]
                       [?e2 :name ?name]]}))
    (is (= p3 '{:plan [[?e2 :name ?name]
                       [?e :is-in ?e2]
                       [?e :name "Washington Monument"]]}))
    ))

#?(:cljs (run-tests))
