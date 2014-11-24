(def ROOT-DIR (subs *file* 0 (- (count *file*) (count "project.clj"))))
; (def HADOOP-CDH-MR1-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-MR1-VERSION") slurp))
; (def HADOOP-CDH-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-VERSION") slurp))
; (def HADOOP-UTIL-VERSION (str "0.3.0-2.0.0-mr1-cdh4.5.0"))
; (def VERSION (-> ROOT-DIR (str "/../VERSION") slurp))
(def HADOOP-CDH-MR1-VERSION (str "2.0.0-mr1-cdh4.5.0"))
(def HADOOP-CDH-VERSION (str "2.0.0-cdh4.5.0"))
(def HADOOP-UTIL-VERSION (str "0.3.0"))
(def VERSION (str "2.1.1"))

(defproject cascalog/cascalog-more-taps VERSION
  :description "More taps for Cascalog"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :jar-exclusions [#"\.java$"]
  :repositories [["clojars.org" "http://clojars.org/repo"]
                 ["conjars.org" "http://conjars.org/repo"]
                 ["cdh.releases.repo" "https://repository.cloudera.com/artifactory/cloudera-repos/"]]
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :provided {:dependencies [[cascalog/cascalog-core ~VERSION]
                                       [org.apache.hadoop/hadoop-core ~HADOOP-CDH-MR1-VERSION]
                                       [org.apache.hadoop/hadoop-common ~HADOOP-CDH-VERSION]
                                       ]}
             :dev {:plugins [[lein-midje "3.1.3"]]
                   :dependencies
                   [[cascalog/midje-cascalog ~VERSION]
                    [hadoop-util ~HADOOP-UTIL-VERSION]
                    ]}})
