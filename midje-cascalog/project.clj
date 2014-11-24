(def ROOT-DIR (subs *file* 0 (- (count *file*) (count "project.clj"))))
; (def HADOOP-CDH-MR1-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-MR1-VERSION") slurp))
; (def VERSION (-> ROOT-DIR (str "/../VERSION") slurp))
(def HADOOP-CDH-MR1-VERSION (str "2.0.0-mr1-cdh4.5.0"))
(def VERSION (str "2.1.1"))

(defproject cascalog/midje-cascalog VERSION
  :description "Cascalog functions for Midje."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["clojars.org" "http://clojars.org/repo"]
                 ["conjars.org" "http://conjars.org/repo"]
                 ["cdh.releases.repo" "https://repository.cloudera.com/artifactory/cloudera-repos/"]]
  :dependencies [[midje "1.5.1" :exclusions [org.clojure/clojure]]]
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :dev {:plugins [[lein-midje "3.1.3"]]}
             :provided {:dependencies [[cascalog/cascalog-core ~VERSION]
                                       [org.apache.hadoop/hadoop-core ~HADOOP-CDH-MR1-VERSION]]}})
