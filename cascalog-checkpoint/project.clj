; (def HADOOP-CDH-MR1-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-MR1-VERSION") slurp))
; (def VERSION (-> ROOT-DIR (str "/../VERSION") slurp))
(def HADOOP-CDH-MR1-VERSION (str "2.0.0-mr1-cdh4.5.0"))
(def VERSION (str "2.1.1"))

(defproject cascalog/cascalog-checkpoint VERSION
  :description "Workflow checkpoints for the masses."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["clojars.org" "http://clojars.org/repo"]
                 ["conjars.org" "http://conjars.org/repo"]
                 ["cdh.releases.repo" "https://repository.cloudera.com/artifactory/cloudera-repos/"]]
  :dependencies [[jackknife "0.1.6"]
                 [hadoop-util "0.3.0"]
                 ; [hadoop-util "0.3.0-2.0.0-mr1-cdh4.5.0"]
                 ]
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :dev {:plugins [[lein-midje "3.1.3"]]}
             :provided {:dependencies [[cascalog/cascalog-core ~VERSION]
                                       [org.apache.hadoop/hadoop-core ~HADOOP-CDH-MR1-VERSION]
                                       ]}})
