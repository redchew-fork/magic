(ns magic.analyzer.typed-passes
  (:require
   [clojure.string :as string]
   [clojure.tools.analyzer.ast :refer [update-children]]
   [magic.analyzer
    [binder :refer [select-method]]
    [loop-bindings :as loop-bindings]
    [novel :as novel]
    [generated-types :as gt]
    [analyze-host-forms :as host]
    [intrinsics :as intrinsics]
    [errors :refer [error] :as errors]
    [types :refer [ast-type class-for-name non-void-ast-type] :as types]]
   [magic.core :as magic]
   [magic.interop :as interop])
  (:import [System Type SByte Int16 UInt16 Int32 UInt32 Char Single IntPtr UIntPtr]))

;; TODO this is duplicated in magic.analyzer.analyze-host-forms
(defn ensure-class
  ([c] (ensure-class c c))
  ([c form]
   (or (class-for-name c)
       (and magic/*module*
            (.GetType magic/*module* (str c)))
       (error
        ::errors/missing-type
        {:type c :form form}))))


(defn analyze-gen-interface
  [{:keys [name methods extends] :as ast}]
  (case (:op ast)
    :gen-interface
    (let [extends* (->> extends
                        (map host/analyze-type)
                        (mapv :val))
          gen-interface-type
          (gt/gen-interface-type magic/*module* (str name) extends*)
          resolve-type
          (fn [t]
            (if (= (str t) (str name))
              gen-interface-type
              (ensure-class t)))]
      (doseq [m methods]
        (let [[name args return] m]
          (.DefineMethod
           gen-interface-type
           (str name)
           (enum-or System.Reflection.MethodAttributes/Public System.Reflection.MethodAttributes/Virtual System.Reflection.MethodAttributes/Abstract)
           (resolve-type return)
           (into-array Type (mapv resolve-type args)))))
      (.CreateType gen-interface-type)
      (assoc ast :gen-interface-type gen-interface-type))
    ast))


(defn field-volatile? [f]
  (boolean (:volatile-mutable (meta f))))

(defn field-mutable? [f]
  (let [m (meta f)]
    (boolean
     (or
      (:unsynchronized-mutable m)
      (:volatile-mutable m)))))

(defn define-special-statics [tb]
  (.DefineMethod
   tb
   "getBasis"
   (enum-or System.Reflection.MethodAttributes/Public System.Reflection.MethodAttributes/Static)
   clojure.lang.IPersistentVector
   Type/EmptyTypes))

;; from https://docs.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/volatile
(defn validate-volatile-field [sym]
  (let [hint (or (types/tag sym) Object)
        valid-enum-types #{SByte Byte Int16 UInt16 Int32 UInt32}
        valid-types (into valid-enum-types [Char Single Boolean IntPtr UIntPtr])]
    (when-not (or (.IsClass hint)
                  (.IsPointer hint)
                  (valid-types hint)
                  (and (.IsEnum hint)
                       (valid-enum-types (Enum/GetUnderlyingType hint))))
      (throw (ex-info "Invalid type used as volatile field"
                      {:symbol sym :type hint :documentation "https://docs.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/volatile"})))))

(defn analyze-deftype
  [{:keys [op name fields implements methods] :as ast}]
  (case op
    :deftype
    (let [name (str name)
          interfaces (->> implements
                          (map host/analyze-type)
                          (mapv :val))
          all-interfaces (into #{} (concat interfaces (mapcat #(.GetInterfaces %) interfaces)))
          candidate-methods (into #{} (concat (.GetMethods Object)
                                              (mapcat #(.GetMethods %) all-interfaces)))
          mutable-attribute System.Reflection.FieldAttributes/Public
          immutable-attribute (enum-or mutable-attribute System.Reflection.FieldAttributes/InitOnly)
          deftype-type
          (reduce
           (fn [t f]
             (.DefineField
              t
              (str f)
              (or (types/tag f) Object)
              (when (field-volatile? f)
                (validate-volatile-field f)
                (into-array Type [System.Runtime.CompilerServices.IsVolatile]))
              nil
              (if (field-mutable? f) mutable-attribute immutable-attribute))
             t)
           (gt/deftype-type magic/*module* name interfaces)
           fields)
          methods*
          (mapv
           (fn [{:keys [params name] :as f}]
             (let [name (str name)
                   params* (drop 1 params) ;; deftype uses explicit this
                   [interface-name method-name]
                   (if (string/includes? name ".")
                     (let [last-dot (string/last-index-of name ".")]
                       [(subs name 0 last-dot)
                        (subs name (inc last-dot))])
                     [nil name])
                   candidate-methods (filter #(= method-name (.Name %)) candidate-methods)
                   candidate-methods (if interface-name
                                       (filter #(= interface-name (.. % DeclaringType FullName)) candidate-methods)
                                       candidate-methods)]
               (if-let [best-method (select-method candidate-methods (map ast-type params*))]
                 (let [hinted-params (mapv #(update %1 :form vary-meta assoc :tag %2) params (concat [deftype-type] (map #(.ParameterType %) (.GetParameters best-method))))]
                   (assoc f
                          :params hinted-params
                          :source-method best-method
                          :deftype-type deftype-type))
                 (throw (ex-info "no match" {:name name :params (map ast-type params)})))))
           methods)]
      (define-special-statics deftype-type)
      (assoc ast
             :deftype-type deftype-type
             :methods methods*
             :implements interfaces))
    ast))

(defn gen-fn-name [n]
  (string/replace
   (str (gensym
         (str *ns* "$" (or n "fn") "$")))
   "."
   "$"))

(defn analyze-fn
  [{:keys [op local methods] :as ast}]
  (case op
    :fn
    (let [name (:form local)
          param-types (->> methods
                           (map :params)
                           (mapcat #(vector (map non-void-ast-type %)
                                            (map (constantly Object) %))))
          return-types (->> methods
                            (mapcat #(vector
                                      (or (-> % :form first meta :tag types/resolve)
                                          (-> % :body non-void-ast-type))
                                      Object)))
          interfaces (map #(interop/generic-type "Magic.Function" (conj %1 %2))
                          param-types
                          return-types)
          fn-type (gt/fn-type magic/*module* (gen-fn-name name) interfaces)]
      (assoc ast :fn-type fn-type))
    ast))

(defn analyze-proxy
  "Typed analysis of proxy forms. Generates a TypeBuilder for this proxy and
   looks up interface/super type methods. magic.core/*module* must be bound
   before this function is called and will contain the generated proxy type when
   this function returns."
  [{:keys [op class-and-interface fns] :as ast}]
  (case op
    :proxy
    (let [class-and-interface (mapv host/analyze-type class-and-interface)
          super-provided? (not (-> class-and-interface first :val .IsInterface))
          super (if super-provided?
                  (:val (first class-and-interface))
                  Object)
          interfaces (mapv :val
                           (if super-provided?
                             (drop 1 class-and-interface)
                             class-and-interface))
          interfaces* (into #{} (concat interfaces (mapcat #(.GetInterfaces %) interfaces)))
          proxy-type (gt/proxy-type magic/*module* super interfaces)
          candidate-methods (into #{} (concat (.GetMethods super)
                                              (mapcat #(.GetMethods %) interfaces*)))
          fns (mapv
               (fn [{:keys [params name] :as f}]
                 (let [name (str name)
                       [interface-name method-name]
                       (if (string/includes? name ".")
                         (let [last-dot (string/last-index-of name ".")]
                           [(subs name 0 last-dot)
                            (subs name (inc last-dot))])
                         [nil name])
                       candidate-methods (filter #(= method-name (.Name %)) candidate-methods)
                       candidate-methods (if interface-name
                                           (filter #(= interface-name (.. % DeclaringType FullName)) candidate-methods)
                                           candidate-methods)]
                   (if-let [best-method (select-method candidate-methods (map ast-type params))]
                     (let [params* (mapv #(update %1 :form vary-meta assoc :tag %2) params (map #(.ParameterType %) (.GetParameters best-method)))]
                       (assoc f
                              :params params*
                              :source-method best-method
                              :proxy-type proxy-type))
                     (throw (ex-info "no match" {:name name :params (map ast-type params)})))))
               fns)
          closed-overs (reduce (fn [co ast] (merge co (:closed-overs ast))) {} fns)
          this-binding-name (->> closed-overs vals (filter #(= :proxy-this (:local %))) first :name)
          closed-overs (dissoc closed-overs this-binding-name)]
      (assoc ast
             :class-and-interface class-and-interface
             :super super
             :interfaces interfaces
             :closed-overs closed-overs
             :fns fns
             :proxy-type proxy-type))
    ast))

(defn analyze-reify
  [{:keys [op interfaces methods] :as ast}]
  (case op
    :reify
    (let [interfaces*
          (conj
           (->> interfaces
                (map host/analyze-type)
                (mapv :val))
           clojure.lang.IObj)
          reify-type (gt/reify-type magic/*module* interfaces*)
          all-interfaces (into #{} (concat interfaces* (mapcat #(.GetInterfaces %) interfaces*)))
          candidate-methods (into #{} (concat (.GetMethods Object)
                                              (mapcat #(.GetMethods %) all-interfaces)))
          methods (mapv
                   (fn [{:keys [params name] :as f}]
                     (let [name (str name)
                           params* (drop 1 params) ;; reify uses explicit this
                           [interface-name method-name]
                           (if (string/includes? name ".")
                             (let [last-dot (string/last-index-of name ".")]
                               [(subs name 0 last-dot)
                                (subs name (inc last-dot))])
                             [nil name])
                           candidate-methods (filter #(= method-name (.Name %)) candidate-methods)
                           candidate-methods (if interface-name
                                               (filter #(= interface-name (.. % DeclaringType FullName)) candidate-methods)
                                               candidate-methods)]
                       (if-let [best-method (select-method candidate-methods (map ast-type params*))]
                         (let [hinted-params (mapv #(update %1 :form vary-meta assoc :tag %2) params (concat [reify-type] (map #(.ParameterType %) (.GetParameters best-method))))]
                           (assoc f
                                  :params hinted-params
                                  :source-method best-method
                                  :reify-type reify-type))
                         (throw (ex-info "no match" {:name name :params (map ast-type params)})))))
                   methods)]
      (assoc ast
             :reify-type reify-type
             :interfaces interfaces*
             :methods methods))
    ast))

(defn typed-pass* [ast]
  (-> ast
      analyze-proxy
      analyze-reify
      analyze-fn
      analyze-deftype
      analyze-gen-interface
      host/analyze-byref
      host/analyze-type
      host/analyze-host-field
      host/analyze-constructor
      host/analyze-host-interop
      host/analyze-host-call
      novel/csharp-operators
      novel/generic-type-syntax
      intrinsics/analyze))

(def ^:dynamic *typed-pass-locals* {})

(defn typed-passes [ast]
  (letfn [(update-closed-overs
            [closed-overs]
            (reduce-kv (fn [m name {:keys [form]}]
                         (if-let [init (*typed-pass-locals* name)]
                           (let [form* (:form init)]
                             (-> m
                                 (assoc-in [name :env :locals form :init] init)
                                 (assoc-in [name :form] form*)))
                           m))
                       closed-overs
                       closed-overs))
          (update-bindings
            [bindings]
            (let [update-binding
                  (fn [{:keys [locals bindings]} {:keys [name] :as binding-ast}]
                    (let [binding-ast* (binding [*typed-pass-locals* locals]
                                         (typed-passes binding-ast))
                          locals* (assoc locals name binding-ast*)]
                      {:locals locals* :bindings (conj bindings binding-ast*)}))]
              (reduce update-binding {:locals *typed-pass-locals* :bindings []} bindings)))]
    (case (:op ast)
      :catch
      (let [{:keys [class local]} ast
            class* (-> class typed-passes :val)]
        (binding [*typed-pass-locals*
                  (assoc *typed-pass-locals* (:name local) class*)]
          (typed-pass* (update-children ast typed-passes))))
      :proxy-super
      (let [args (:args ast)
            env (:env ast)
            method-name (str (:method ast))
            proxy-this-binding (-> ast :env :locals (get 'this))
            this-name (:name proxy-this-binding)
            proxy-type (*typed-pass-locals* this-name)
            super-type (.BaseType proxy-type)
            candidate-methods
            (->> (.GetMethods super-type)
                 (filter #(= (.Name %) method-name)))
            args* (mapv typed-passes args)
            arg-types (map ast-type args*)]
        (if-let [best-method (select-method candidate-methods arg-types)]
          {:op :instance-method
           :non-virtual? true
           :method best-method
           :target {:op :local :local :proxy-this :proxy-type super-type :env env :form 'this}
           :args args*
           :env env
           :children [:args]}
          (throw (ex-info "Could not bind proxy-super to base class method"
                          {:method-name method-name :arg-types arg-types :form (:form ast)}))))
      :proxy
      (let [ast* (analyze-proxy ast)
            this-name (-> ast* :this-binding :name)
            proxy-type (:proxy-type ast*)]
        (binding [*typed-pass-locals* (assoc *typed-pass-locals* this-name proxy-type)]
          (let [ast** (update-children ast* typed-passes)
              ;; maybe move ths closed overs part into compiler
                closed-overs (reduce (fn [co ast] (merge co (:closed-overs ast))) {} (:fns ast**))
                this-binding-name (->> closed-overs vals (filter #(= :proxy-this (:local %))) first :name)
                closed-overs (dissoc closed-overs this-binding-name)]
            (assoc ast**
                   :closed-overs closed-overs))))
      :reify
      (let [ast* (analyze-reify ast)]
        (println "[:reify]" (:children ast*))
        (let [ast** (update-children ast* typed-passes)
              ;; maybe move ths closed overs part into compiler
              ;; closed-overs (reduce (fn [co ast] (merge co (:closed-overs ast))) {} (:fns ast**))
              ;; this-binding-name (->> closed-overs vals (filter #(= :proxy-this (:local %))) first :name)
              ;; closed-overs (dissoc closed-overs this-binding-name)
              ]
          (update ast** :closed-overs update-closed-overs)))
      :deftype
      (let [ast* (analyze-deftype ast)]
        (update-children ast* typed-passes))
      (:let :loop)
      (let [{:keys [bindings body]} ast
            {locals* :locals bindings* :bindings} (update-bindings bindings)]
        (binding [*typed-pass-locals* locals*]
          (loop-bindings/infer-binding-types
           (assoc ast
                  :bindings bindings*
                  :body (typed-passes body)))))
      :local
      (let [{:keys [name form local]} ast]
        (case local
          :proxy-this
          (if-let [local-type (*typed-pass-locals* name)]
            (assoc ast :proxy-type local-type)
            (throw (ex-info "Local not found in environment"
                            {:local name :form form})))
          :catch
          (if-let [local-type (*typed-pass-locals* name)]
            (update ast :form vary-meta merge {:tag local-type})
            (throw (ex-info "Local not found in environment"
                            {:local name :form form})))
          :arg
          (if-let [init (*typed-pass-locals* name)]
            (do
              (println "[local :arg]" (:form ast) (-> init :form meta :tag))
              (update ast :form vary-meta assoc :tag (-> init :form meta :tag)))
            ast)
          (:let :loop)
          (if-let [init (*typed-pass-locals* name)]
            (assoc-in ast [:env :locals form :init] init)
            (throw (ex-info "Local not found in environment"
                            {:local name :form form})))
          #_:else
          ast))
      :proxy-method
      (let [closed-overs (:closed-overs ast)
            closed-overs* (update-closed-overs closed-overs)
            {locals* :locals} (update-bindings (:params ast))]
        (binding [*typed-pass-locals* locals*]
          (typed-pass*
           (assoc
            (update-children ast typed-passes)
            :closed-overs closed-overs*))))
      :reify-method
      (let [{locals* :locals} (update-bindings (:params ast))]
        (binding [*typed-pass-locals* locals*]
          (typed-pass*
           (update-children ast typed-passes))))
      :deftype-method
      (let [{locals* :locals} (update-bindings (:params ast))]
        (binding [*typed-pass-locals* locals*]
          (typed-pass*
           (update-children ast typed-passes))))
      (:fn :try)
      (if-let [closed-overs (:closed-overs ast)]
        (let [closed-overs* (update-closed-overs closed-overs)]
          (typed-pass*
           (update-children (assoc ast :closed-overs closed-overs*) typed-passes)))
        (typed-pass* (update-children ast typed-passes)))
      #_:else
      (typed-pass* (update-children ast typed-passes)))))