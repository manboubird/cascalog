(def hadoop-core-version "2.0.0-mr1-cdh4.3.0")
(defproject cascalog/cascalog-more-taps "1.10.2-mr1-cdh4.3.0"
  :description "More taps for Cascalog"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"conjars.org" "http://conjars.org/repo"
                 "cloudera-releases" "https://repository.cloudera.com/artifactory/cloudera-repos"}
  :plugins [[lein-midje "3.0.1"]]
  :profiles {:1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :provided {:dependencies [[cascalog/cascalog-core "1.10.2-mr1-cdh4.3.0"]]}
             :dev {:dependencies [[org.apache.hadoop/hadoop-core ~hadoop-core-version]
                                  [cascalog/midje-cascalog "1.10.2-mr1-cdh4.3.0"]
                                  [org.clojure/clojure "1.5.1"]]}})
