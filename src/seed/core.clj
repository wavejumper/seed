(ns seed.core
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [clojure.core.protocols :as p]
            [com.climate.claypoole :as cp]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (com.jcraft.jsch JSch ChannelSftp ChannelSftp$LsEntry SftpATTRS Session)
           (java.util.concurrent Executors Future ExecutorService CountDownLatch))
  (:gen-class))

(extend-protocol p/Datafiable
  SftpATTRS
  (datafy [this]
    {:size   (.getSize this)
     :link?  (.isLink this)
     :dir?   (.isDir this)
     :m-time (.getMTime this)
     :a-time (.getATime this)})

  ChannelSftp$LsEntry
  (datafy [this]
    {:attrs    (p/datafy (.getAttrs this))
     :filename (.getFilename this)}))

(defn home []
  (System/getProperty "user.home"))

(defn home-known-hosts []
  (str (home) "/.ssh/known_hosts"))

(defn sftp-client
  [{:keys [known-hosts host username password port]
    :or   {port 22}}]
  (let [jsch    (doto (JSch.)
                  ;; ssh-keyscan -H -t rsa example.org >> known_hosts
                  (.setKnownHosts ^String (or known-hosts (home-known-hosts))))
        session (doto (.getSession jsch username host port)
                  (.setPassword ^String password)
                  (.connect))
        channel (.openChannel session "sftp")]
    (.connect channel)
    {:jsch jsch :session session :channel channel}))

(defmethod ig/init-key :sftp/pool
  [_ {:keys [n-conns] :or {n-conns 5} :as opts}]
  {:pool    (repeatedly n-conns #(sftp-client opts))
   :n-conns n-conns})

(defmethod ig/halt-key! :sftp/pool
  [_ {:keys [pool]}]
  (doseq [client pool]
    (when-let [^Session session (:session client)]
      (.disconnect session))

    (when-let [^ChannelSftp channel (:channel client)]
      (.disconnect channel))))

(defmethod ig/init-key :sftp/target
  [_ opts]
  (log/infof "Target: %s" opts)
  opts)

(defmulti filter-fn (fn [[id & _]] id))

(defmethod filter-fn :default
  [[id & _]]
  (log/errorf "No filter-fn found with id %s" id)
  (constantly false))

(defmethod filter-fn :some-ext
  [[_ exts]]
  (fn [{:keys [filename]}]
    (some #(str/ends-with? filename %) exts)))

(defn transfer-file
  [conn source-dir dir file]
  (log/infof "Transferring %s to %s" file dir)
  (io/make-parents (str dir "/" file))
  (.get ^ChannelSftp (:channel conn)
        (str source-dir "/" file)
        (str dir "/" file))
  (log/infof "Transferred %s to %s" file dir))

(defn source->target
  [{:keys [n-conns pool]} source-files source-dir {:keys [dir filters]}]
  (let [filter-fn          (apply every-pred (map filter-fn filters))
        files              (into [] (comp (filter filter-fn)
                                          (map (fn [{:keys [filename]}]
                                                 (subs filename (-> source-dir count inc)))))
                                 source-files)
        n-files            (count files)
        n-execution        (max (long (/ n-files n-conns)) 1)
        batched-files      (partition-all n-execution files)
        batched-executions (partition 2 (interleave (cycle pool) batched-files))
        start              (System/currentTimeMillis)]
    (log/infof "[%s] files matched for dest %s" n-files dir)
    (cp/pdoseq
     (count batched-executions)
     [[conn files] batched-executions]
     (doseq [^String file files]
       (transfer-file conn source-dir dir file)))
    (log/infof "[%s] files transferred to dest %s in [%s ms]" n-files dir (- (System/currentTimeMillis) start))))

(defn list-source-files
  [{:keys [n-conns pool] :as conn-pool} conn depth cache ^String dir]
  (let [next-files (locking conn
                     (into [] (comp (map p/datafy)
                                    (remove (comp #{"." ".."} :filename))
                                    (map (fn [x] (update x :filename #(str dir "/" %))))
                                    (remove (fn [{:keys [filename attrs]}]
                                              (when-let [cache-m-time (get cache filename)]
                                                (>= (:m-time attrs) cache-m-time)))))
                           (.ls ^ChannelSftp (:channel conn) dir)))
        next-dirs  (into [] (comp (filter #(-> % :attrs :dir?))
                                  (map :filename))
                         next-files)
        next-depth (dec depth)
        next-cache (into cache (map (juxt :filename #(-> % :attrs :m-time))) next-files)]
    (if (zero? next-depth)
      {:files next-files :cache next-cache}
      (let [batched-dirs       (partition-all n-conns next-dirs)
            batched-executions (partition 2 (interleave (cycle pool) batched-dirs))
            recursive-results  (mapcat identity
                                       (cp/pmap n-conns
                                                (fn [[conn dirs]]
                                                  (doall (map (partial list-source-files conn-pool conn next-depth next-cache)
                                                              dirs)))
                                                batched-executions))]
        {:files (into next-files (mapcat :files) recursive-results)
         :cache (into next-cache (mapcat :cache) recursive-results)}))))

(defn load-cache [cache-file]
  (if (.exists (io/file cache-file))
    (edn/read-string (slurp cache-file))
    {}))

(defn source-loop
  [{:keys [pool dir targets poll-ms depth cache]
    :or   {depth 1 cache "cache.edn"}}]
  (loop [curr-cache (load-cache cache)]
    (let [start      (System/currentTimeMillis)
          result     (list-source-files pool (-> pool :pool first) depth curr-cache dir)
          files      (remove #(-> % :attrs :dir?) (:files result))
          end        (System/currentTimeMillis)
          elapsed    (- end start)
          n-files    (count files)
          next-cache (:cache result)]
      (log/infof "Found [%s] new files in %s [elapsed %s ms] " n-files dir elapsed)
      (when-not (zero? n-files)
        (doseq [target targets]
          (source->target pool files dir target)))
      (spit cache (pr-str next-cache))
      (Thread/sleep poll-ms)
      (recur next-cache))))

(defmethod ig/init-key :sftp/source
  [_ opts]
  (let [pool (Executors/newSingleThreadExecutor)]
    {:job  (.submit pool ^Runnable (fn []
                                     (try (source-loop opts)
                                          (catch InterruptedException _)
                                          (catch Throwable e
                                            (log/error e "Error in source-loop, exiting")
                                            (System/exit 1)))))
     :pool pool}))

(defmethod ig/halt-key! :sftp/source
  [_ {:keys [job pool]}]
  (.cancel ^Future job true)
  (.shutdown ^ExecutorService pool))

(defn readers []
  (merge *data-readers*
         {'ig/ref    ig/ref
          'ig/refset ig/refset}))

(defn -main
  [config-path & _]
  (try (let [opts   {:readers (readers) :eof nil}
             config (edn/read-string opts (slurp config-path))
             system (ig/init config)
             latch  (CountDownLatch. 1)]
         (.addShutdownHook (Runtime/getRuntime)
                           (Thread. ^Runnable (fn []
                                                (.countDown latch))))
         (.await latch)
         (ig/halt! system)
         (System/exit 0))
       (catch Throwable e
         (log/error e)
         (System/exit 1))))