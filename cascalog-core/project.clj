(def ROOT-DIR (subs *file* 0 (- (count *file*) (count "project.clj"))))
; (def HADOOP-CDH-MR1-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-MR1-VERSION") slurp))
; (def HADOOP-CDH-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-VERSION") slurp))
; (def VERSION (-> ROOT-DIR (str "/../VERSION") slurp))
(def HADOOP-CDH-MR1-VERSION (str "2.0.0-mr1-cdh4.5.0"))
(def HADOOP-CDH-VERSION (str "2.0.0-cdh4.5.0"))
(def VERSION (str "2.1.1"))
(def CC-VERSION (or (System/getenv "CASCALOG_CASCADING_VERSION") "2.5.3"))


(defproject cascalog/cascalog-core VERSION
  :description "Cascalog core libraries."
  :url "http://www.cascalog.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-Xmx768m"
             "-server"
             "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :jar-exclusions [#"\.java$"]
  :repositories [["clojars.org" "http://clojars.org/repo"]
                 ["conjars.org" "http://conjars.org/repo"]
                 ["cdh.releases.repo" "https://repository.cloudera.com/artifactory/cloudera-repos/"]]
  :exclusions [log4j/log4j org.slf4j/slf4j-log4j12]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [log4j "1.2.16"]
                 [org.slf4j/slf4j-log4j12 "1.6.6"]
                 [cascading/cascading-hadoop ~CC-VERSION
                  :exclusions [org.codehaus.janino/janino
                               org.apache.hadoop/hadoop-core]]
                 [com.twitter/chill-hadoop "0.3.5"]
                 [com.twitter/carbonite "1.4.0"]
                 [com.twitter/maple "0.2.2"]
                 [jackknife "0.1.7"]
                 [hadoop-util "0.3.0"]
                 [org.apache.hadoop/hadoop-core ~HADOOP-CDH-MR1-VERSION]
                 [org.apache.hadoop/hadoop-common ~HADOOP-CDH-VERSION]
                 ]
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             ; :provided {:dependencies [[org.apache.hadoop/hadoop-core ~HADOOP-CDH-MR1-VERSION]
             ;                           [org.apache.hadoop/hadoop-common ~HADOOP-CDH-VERSION]
             ;                           ]}
             :dev {:resource-paths ["dev"]
                   :plugins [[lein-midje "3.1.3"]]
                   :dependencies
                   [[cascalog/midje-cascalog ~VERSION]]}})
