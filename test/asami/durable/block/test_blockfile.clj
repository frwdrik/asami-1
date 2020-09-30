(ns ^{:doc "Tests for the BlockFile implementation"
      :author "Paula Gearon"}
    asami.durable.block.test-blockfile
  (:require [clojure.test :refer :all]
            [asami.durable.block.block-api :refer :all]
            [asami.durable.block.file.block-file :refer :all]
            [asami.durable.block.file.voodoo :as voodoo]
            [asami.durable.block.file.util :as util])
  (:import [java.nio.charset Charset]))

(def test-block-size 256)

(def str0 "String in block 0.")

(def str1 "String in block 1.")

(def str2 "String in block 2.")

(def str3 "String in block 3.")

(defn cleanup
  []
  (when voodoo/windows?
    (System/gc)
    (System/runFinalization)))

(defn exec-bf
  [filename f]
  (let [filename (util/temp-file filename)
        {:keys [block-file file]} (open-block-file filename test-block-size)]
    (try
      (f file block-file)
      (finally
        (clear! block-file)
        (unmap block-file)
        (cleanup)))))

(defmacro with-block-file
  "Executes the body in a context of an unmanaged block file"
  [filename body]
  `(exec-bf ~filename (fn [bf af] ~@body)))

(def utf8 (Charset/forName "UTF-8"))

(defn put-string! [b s]
  (let [^bytes bytes (.getBytes s utf8)
        len (count bytes)]
    (put-byte! b 0 len)
    (put-bytes! b 1 len bytes)))

(defn get-string [b]
  (let [l (get-byte b 0)
        d (get-bytes b 1 l)]
    (String. d utf8)))

(deftest test-allocate
  (let [filename (util/temp-file "ualloc")
        block-file (open-block-file filename test-block-size)
        block-file (set-nr-blocks! block-file 1)]
    (try
      (let [blk (block-for block-file 0)]
        (is (not (nil? blk))))
      (finally
        (clear! block-file)
        (unmap block-file)
        (cleanup)))))

(deftest test-write
  (let [file-str "bftest"
        filename (util/temp-file file-str)
        block-file (open-block-file filename test-block-size)
        bf (set-nr-blocks! block-file 4)
        b (block-for bf 0)
        _ (put-string! b str0)
        b (block-for bf 3)
        _ (put-string! b str3)
        b (block-for bf 2)
        _ (put-string! b str2)
        b (block-for bf 1)
        _ (put-string! b str1)]
    
    (is (= str2 (get-string (block-for bf 2))))
    (is (= str0 (get-string (block-for bf 0))))
    (is (= str1 (get-string (block-for bf 1))))
    (is (= str3 (get-string (block-for bf 3))))
    
    ;; close all, and start again
    (unmap bf)
    (cleanup)

    (let [block-file (open-block-file filename test-block-size)]
      
      ;; did it persist
      
      (is (= 4 (get-nr-blocks block-file)))
      
      (is (= str2 (get-string (block-for block-file 2))))
      (is (= str0 (get-string (block-for block-file 0))))
      (is (= str1 (get-string (block-for block-file 1))))
      (is (= str3 (get-string (block-for block-file 3))))

      (clear! block-file)
      (unmap block-file)
      (cleanup))))

(deftest test-performance
  (let [file-str "perftest"
        filename (util/temp-file file-str)
        block-file (open-block-file filename test-block-size)
        block-file (clear! block-file)
        nr-blocks 100000
        bf (set-nr-blocks! block-file nr-blocks)]

    (try
      (doseq [i (range nr-blocks)]
        (let [b (block-for bf i)]
          (put-int! b 0 (+ i 5))))

      (doseq [i (range nr-blocks)]
        (let [b (block-for bf i)]
          (is (= (+ i 5) (get-int b 0)))))

      (doseq [pass (range 10)]
        (doseq [i (range nr-blocks)]
          (let [b (block-for bf i)]
            (put-int! b 0 (bit-xor i pass))))
        (doseq [i (range nr-blocks)]
          (let [b (block-for bf i)]
            (is (= (bit-xor i pass) (get-int b 0))))))

      (finally
        (clear! bf)
        (unmap bf)
        (cleanup)))))

