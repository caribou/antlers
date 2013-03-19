(ns antlers.core
  (:require [clojure.string :as string])
  (:use [antlers.parser :exclude [partial]]
        [antlers.ast :rename {render node-render
                              partial node-partial}]
        [quoin.text :as qtext]
        [clojure.java.io :only [resource]]
        antlers.utils))

(declare render)
(declare render-string)

;; Clojure can't do circular dependencies between namespaces.
;; Some types need access to render/render-string to do what they are
;; supposed to do. But render-string depends on parser, parser depends on ast,
;; and to implement, ast would have to depend on core. So instead of doing what
;; Clojure wants you to do, and jam it all into one huge file, we're going to
;; just implement ASTNode for some of the ASTNode types here.

(defn build-loop-vars
  [items item item-binding index items-count stack]
  (let [outer (or (context-get stack (list 'loop)) {})]
    {:loop
     {:outer outer
      :item item
      :count items-count
      :index index
      :inc-index (inc index)
      :first (zero? index)
      :last (>= index (dec items-count))}
     item-binding item}))

(extend-protocol ASTNode
  antlers.ast.Section
  (render [this ^StringBuilder sb context-stack]
    (let [ctx-val (context-get context-stack (:name this))]
      (cond (or (not ctx-val) ;; "False" or the empty list -> do nothing.
                (= "" ctx-val)
                (and (sequential? ctx-val)
                     (empty? ctx-val)))
            nil

            ;; Non-empty list -> Display content once for each item in list.
            (sequential? ctx-val)
            (let [items-count (count ctx-val)
                  item-binding (-> this :attrs :item-binding)]
              (loop [items ctx-val
                     index 0]
                (if (not (empty? items))
                  (let [item (first items)
                        loop-vars (build-loop-vars ctx-val item item-binding index items-count context-stack)]
                    (node-render (:contents this) sb (conj (conj context-stack loop-vars) item))
                    (recur (rest items) (inc index))))))

            ;; (doseq [val ctx-val]
            ;;   ;; For each render, push the value to top of context stack.
            ;;   (node-render (:contents this) sb (conj context-stack val)))

            ;; Callable value -> Invoke it with the literal block of src text.
            (instance? clojure.lang.Fn ctx-val)
            (let [current-context (first context-stack)
                  lambda-return (call-lambda ctx-val (:content (:attrs this))
                                             current-context)]
              ;; We have to manually parse because the spec says lambdas in
              ;; sections get parsed with the current parser delimiters.
              (.append sb (render (parse lambda-return
                                         (select-keys (:attrs this)
                                                      [:tag-open :tag-close]))
                                         current-context)))
            ;; Non-false non-list value -> Display content once.
            :else
            (node-render (:contents this) sb (conj context-stack ctx-val)))))

  antlers.ast.Block
  (render [this ^StringBuilder sb context-stack]
    (node-render (:contents this) sb context-stack))

  antlers.ast.EscapedVariable
  (render [this ^StringBuilder sb context-stack]
    (if-let [value (context-get context-stack (:name this))]
      (if (instance? clojure.lang.Fn value)
        (.append sb (qtext/html-escape
                     (render-string (str (call-lambda value
                                                      (first context-stack)))
                                    (first context-stack))))
        ;; Otherwise, just append its html-escaped value by default.
        (.append sb (qtext/html-escape (str value))))))

  antlers.ast.UnescapedVariable
  (render [this ^StringBuilder sb context-stack]
    (if-let [value (context-get context-stack (:name this))]
      (if (instance? clojure.lang.Fn value)
        (.append sb (render-string (str (call-lambda value
                                                     (first context-stack)))
                                   (first context-stack)))
        ;; Otherwise, just append its value.
        (.append sb value)))))

(defn render
  "Given a parsed template (output of load or parse) and map of args,
   renders the template."
  [template data-map]
  (let [sb (StringBuilder.)
        context-stack (conj '() data-map)]
    (node-render template sb context-stack)
    (.toString sb)))

(defn render-file
  "Given a template name (string) and map of args, loads and renders the named
   template."
  [template-name data-map]
  (render (load-template template-name) data-map))

(defn render-string
  "Renders a given string containing the source of a template and a map
   of args."
  [template-src data-map]
  (render (parse template-src) data-map))
