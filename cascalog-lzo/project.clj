(def ROOT-DIR (subs *file* 0 (- (count *file*) (count "project.clj"))))
; (def HADOOP-CDH-MR1-VERSION (-> ROOT-DIR (str "/../HADOOP-CDH-MR1-VERSION") slurp))
; (def VERSION (-> ROOT-DIR (str "/../VERSION") slurp))
(def HADOOP-CDH-MR1-VERSION (str "2.0.0-mr1-cdh4.5.0"))
(def VERSION (str "2.1.1"))

(defproject cascalog/cascalog-lzo VERSION
  :description "Lzo compression taps for Cascalog."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["clojars.org" "http://clojars.org/repo"]
                 ["conjars.org" "http://conjars.org/repo"]
                 ["cdh.releases.repo" "https://repository.cloudera.com/artifactory/cloudera-repos/"]]
  :dependencies [[com.twitter.elephantbird/elephant-bird-cascading2 "3.0.7"
                  :exclusions [cascading/cascading-hadoop]]
                 [hadoop-lzo "0.4.15"]]
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :provided {:dependencies [[cascalog/cascalog-core ~VERSION]
                                       [org.apache.hadoop/hadoop-core ~HADOOP-CDH-MR1-VERSION]
                                       [org.apache.httpcomponents/httpclient "4.2.3"]]}
             :dev {:dependencies [[cascalog/midje-cascalog ~VERSION]]
                   :plugins [[lein-midje "3.1.3"]]}})
