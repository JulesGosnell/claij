(require '[claij.new.validation :as v])

;; Test draft 2020-12 specific feature: prefixItems
(def schema-with-prefix-items
  {:type "array"
   :prefixItems [{:type "number"} {:type "string"}]
   :items false})

(println "Test draft 2020-12 feature (prefixItems):")
(println "Valid:")
(prn (v/validate-response [42 "hello"] schema-with-prefix-items))
(println "\nInvalid (extra items):")
(prn (v/validate-response [42 "hello" "extra"] schema-with-prefix-items))
(println "\nInvalid (wrong type):")
(prn (v/validate-response ["not-a-number" "hello"] schema-with-prefix-items))
