(ns anveomg.config
  (:require [anveomg.phone-number]
            [clojure.edn]))

(defn load-config
  [filename] 
  (let [config (clojure.edn/read-string (slurp filename))]
    (update-in config [:anveo :my-number] anveomg.phone-number/format-anveo)))

