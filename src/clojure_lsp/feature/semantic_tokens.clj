(ns clojure-lsp.feature.semantic-tokens
  (:require
   [clojure-lsp.shared :as shared]
   [taoensso.timbre :as log])
  (:import
   [clojure.lang PersistentVector]))

(set! *warn-on-reflection* true)

(def token-types
  [:type
   :function
   :macro
   :keyword
   :class
   :variable
   :method])

(def token-types-str
  (->> token-types
       (map name)
       vec))

(def token-modifiers
  [])

(def token-modifier -1)

(defn ^:private element-inside-range?
  [{element-row :name-row element-end-row :name-end-row}
   {:keys [name-row name-end-row]}]
  (and (>= element-row name-row)
       (<= element-end-row name-end-row)))

(defn ^:private element->absolute-token
  [{:keys [name-row name-col name-end-col]}
   token-type]
  [(dec name-row)
   (dec name-col)
   (- name-end-col name-col)
   (.indexOf ^PersistentVector token-types token-type)
   token-modifier])

(defn ^:private var-usage-element->absolute-tokens
  [{:keys [alias name-col to] :as element}]
  (cond
    (identical? :clj-kondo/unknown-namespace to)
    nil

    alias
    (let [slash-pos (+ name-col (count (str alias)))
          alias-pos (assoc element :name-end-col slash-pos)
          name-pos (assoc element :name-col (inc slash-pos))]
      [(element->absolute-token alias-pos :type)
       (element->absolute-token name-pos :function)])

    :else
    [(element->absolute-token element :function)]))

(defn ^:private elements->absolute-tokens
  [elements]
  (->> elements
       (sort-by (juxt :name-row :name-col))
       (map
         (fn [{:keys [name bucket macro to] :as element}]
           (cond
             (and (= bucket :var-usages)
                  macro)
             [(element->absolute-token element :macro)]

             ;; TODO needs better way to know it's class related
             ;; (and (= bucket :var-usages)
             ;;      (not alias)
             ;;      (Character/isUpperCase (.charAt ^String (str name) 0)))
             ;; [(element->absolute-token element :class)]

             (and (identical? :clj-kondo/unknown-namespace to)
                  (.equals \. (.charAt ^String (str name) 0)))
             [(element->absolute-token element :method)]

             (= bucket :var-usages)
             (var-usage-element->absolute-tokens element)

             (#{:locals :local-usages} bucket)
             [(element->absolute-token element :variable)]

             (and (= bucket :keywords)
                  (not (:str element))
                  (not (:keys-destructuring element)))
             [(element->absolute-token element :keyword)])))
       (remove nil?)
       (mapcat identity)))

(defn ^:private absolute-token->relative-token
  [tokens
   index
   [row col length token-type token-modifier :as token]]
  (let [[previous-row previous-col _ _ _] (nth tokens (dec index) nil)]
    (cond
      (nil? previous-row)
      token

      (= previous-row row)
      [0
       (- col previous-col)
       length
       token-type
       token-modifier]

      :else
      [(- row previous-row)
       col
       length
       token-type
       token-modifier])))

(defn full-tokens [uri db]
  (let [elements (get-in @db [:analysis (shared/uri->filename uri)])
        absolute-tokens (elements->absolute-tokens elements)]
    (->> absolute-tokens
         (map-indexed (partial absolute-token->relative-token absolute-tokens))
         flatten)))

(defn range-tokens
  [uri range db]
  (let [elements (get-in @db [:analysis (shared/uri->filename uri)])
        range-elements (filter #(element-inside-range? % range) elements)
        absolute-tokens (elements->absolute-tokens range-elements)]
    (->> absolute-tokens
         (map-indexed (partial absolute-token->relative-token absolute-tokens))
         flatten)))

(defn element->token-type [element]
  (->> [element]
       elements->absolute-tokens
       (mapv (fn [[_ _ _ type modifier]]
               {:token-type (nth token-types type type)
                :token-modifier (nth token-modifiers modifier modifier)}))))
