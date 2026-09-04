(ns isaac.worksite.turnstile
  "Opt-in :worksite turnstile. Params nil/empty = infer member from ctx :cwd;
   params [\"name\"] = explicit worksite name."
  (:require
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.turnstile :as turnstile]
    [isaac.worksite.lock :as lock]
    [isaac.worksite.registry :as registry]))

(defn- isaac-root []
  (or (nexus/get :root)
      (loader/root)
      (root/current-root)
      (root/default-root)))

(defn- explicit-name [params]
  (cond
    (sequential? params) (first params)
    (string? params) params
    :else nil))

(defn- resolve-name [params ctx]
  (or (explicit-name params)
      (registry/member-of (:cwd ctx))))

(defn- busy-message [name]
  (str name " locked"))

(defn worksite
  "Factory for the :worksite turnstile. Registration is not activation —
   only submitted refs take or consult locks."
  ([] (worksite nil))
  ([params]
   (reify turnstile/Turnstile
     (admit? [_ ctx]
       (let [root (isaac-root)
             name (resolve-name params ctx)]
         (if (nil? name)
           :pass
           (let [taken (lock/acquire-turn! root name {:session-key (:session-key ctx)})]
             (if (:ok taken)
               {:status :pass
                :token  (turnstile/->ReleaseToken
                          {:worksite name :lock-token (:token taken)})}
               {:refuse  :worksite-busy
                :message (busy-message name)})))))
     (release! [_ token]
       (let [payload (cond
                       (instance? isaac.turnstile.ReleaseToken token) (:id token)
                       (map? token) token
                       :else nil)
             name    (or (when (map? payload) (:worksite payload))
                         (resolve-name params nil))
             tok     (when (map? payload) (:lock-token payload))]
         (when name
           (lock/release-turn! (isaac-root) name tok))
         nil)))))
