(ns isaac.worksite.handbook-chapter-spec
  "Lint for isaac-worksite's own handbook chapter (isaac-5f0m): every backtick
   `config:<path>` reference must resolve against the composed config schema,
   and every `isaac <command>` invocation must name a registered top-level
   CLI command. See the convention comment at the top of the chapter file
   itself, and isaac.foundation.handbook-chapter-spec for the pattern this
   follows.

   Unlike isaac-imessage (isaac-6nfg), isaac-worksite's manifest already
   declares :builtin? true, so its own isaac-manifest.edn resource (on this
   module's own classpath: resources/isaac-manifest.edn, per deps.edn :paths)
   is picked up by isaac.module.discovery/builtin-index's own classpath scan
   without any manual index merge."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.schema.resolve :as schema-resolve]
    [isaac.fs :as fs]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private chapter-resource "isaac/worksite/handbook.md")
(def ^:private manifest-resource "isaac-manifest.edn")

(defn- chapter-text []
  (some-> (io/resource chapter-resource) slurp))

(defn- own-manifest []
  (some-> (io/resource manifest-resource) slurp clojure.edn/read-string))

(defn- config-refs
  "Backtick `config:<path>` references in `text`, skipping `<placeholder>`
   shapes (any reference whose path still contains an angle bracket)."
  [text]
  (->> (re-seq #"`config:([^`]+)`" text)
       (map second)
       (remove #(str/includes? % "<"))
       distinct))

(defn- cli-commands-mentioned
  "The word immediately following `isaac ` wherever it appears — inline
   code, fenced examples, or plain prose — for every top-level `isaac
   <command>` invocation in `text`."
  [text]
  (->> (re-seq #"isaac\s+([a-zA-Z][a-zA-Z0-9_-]*)" text)
       (map second)
       distinct))

(defn- known-cli-commands
  "Top-level command names contributed to the :isaac/cli berth by every
   module in `index` — read directly off each module's manifest rather than
   through isaac.module.berths, following isaac.comm.imessage's
   handbook-chapter-lint pattern."
  [index]
  (->> (vals index)
       (mapcat (fn [entry] (keys (get-in entry [:manifest :isaac/cli]))))
       (map name)
       set))

(describe "isaac.worksite handbook chapter (isaac-5f0m)"

  (around [example] (nexus/-with-nexus {:fs (fs/real-fs)} (example)))

  (it "manifest declares the handbook resource"
    (should= chapter-resource (:handbook (own-manifest))))

  (it "ships at the manifest's declared classpath resource"
    (should-not-be-nil (chapter-text)))

  (it "the manifest's own module id is present in the builtin index"
    (should-contain (:id (own-manifest)) (set (keys (discovery/builtin-index)))))

  (it "every `config:<path>` reference resolves against the composed config schema"
    (let [text        (chapter-text)
          root-schema (schema-compose/effective-root-schema (discovery/builtin-index))
          unresolved  (remove #(schema-resolve/schema-for-data-path root-schema %)
                              (config-refs text))]
      (should= [] unresolved)))

  (it "every `isaac <command>` invocation names a registered top-level CLI command"
    (let [text    (chapter-text)
          known   (known-cli-commands (discovery/builtin-index))
          unknown (remove known (cli-commands-mentioned text))]
      (should= [] unknown))))
