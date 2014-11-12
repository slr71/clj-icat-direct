(ns clj-icat-direct.icat
  (:use [clojure.java.io :only [file]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [korma.db :as db]
            [korma.core :as k]
            [clj-icat-direct.queries :as q])
  (:import [clojure.lang ISeq]))


(defn icat-db-spec
  "Creates a Korma db spec for the ICAT."
  [hostname user pass & {:keys [port db]
                         :or {port 5432
                              db "ICAT"}}]
  (db/postgres {:host     hostname
                :port     port
                :db       db
                :user     user
                :password pass}))

(defn setup-icat
  "Defines the icat database. Pass in the return value of icat-db-spec."
  [icat-db-spec]
  (db/defdb icat icat-db-spec))

(defn- run-simple-query
  "Runs one of the defined queries against the ICAT. It's considered a simple query if it doesn't
   require string formatting."
  [query-kw & args]
  (if-not (contains? q/queries query-kw)
    (throw (Exception. (str "query " query-kw " is not defined."))))

  (k/exec-raw icat [(get q/queries query-kw) args] :results))

(defn- run-query-string
  "Runs the passed in query string. Doesn't check to see if it's defined in
   clj-icat-direct.queries first."
  [query & args]
  (k/exec-raw icat [query args] :results))

(defn number-of-files-in-folder
  "Returns the number of files in a folder that the user has access to."
  [user zone folder-path]
  (-> (run-simple-query :count-files-in-folder user zone folder-path) first :count))

(defn number-of-folders-in-folder
  "Returns the number of folders in the specified folder that the user has access to."
  [user zone folder-path]
  (-> (run-simple-query :count-folders-in-folder user zone folder-path) first :count))


(defn ^Integer number-of-items-in-folder
  "Returns the total number of files and folders in the specified folder that the user has access
   to and where the files have the given info types.

   Parameters:
     user        - the username of the user
     zone        - the user's authentication zone
     folder-path - the absolute path to the folder being inspected
     info-types  - the info-types of the files to count, if empty, all files are counted

   Returns:
     It returns the total number of folders combined with the total number of files with the given
     info types."
  [^String user ^String zone ^String folder-path ^ISeq info-types]
  (let [query (format (:count-items-in-folder q/queries) (q/mk-file-type-cond info-types))]
    (-> (run-query-string query user zone folder-path) first :total)))


(defn number-of-all-items-under-folder
  "Returns the total number of files and folders in the specified folder and all
   sub-folders that the user has access to."
  [user zone folder-path]
  (-> (run-simple-query :count-all-items-under-folder user zone folder-path folder-path)
      (first)
      (:total)))


(defn ^Integer number-of-bad-items-in-folder
  "Returns the total number of files and folders in the specified folder that the user has access to
   and where the files have the given info types, but should be marked as having a bad name in the
   client.

   Parameters:
     user        - the username of the authorized user
     zone        - the user's authentication zone
     folder-path - The absolute path to the folder of interest
     info-types  - the info-types of the files to count, if empty, all files are considered
     bad-chars   - If a name contains one or more of these characters, the item will be marked as
                   bad
     bad-names   - This is a sequence of names that are bad
     bad-paths   - This is an array of paths to items that will be marked as badr.

   Returns:
     It returns the total."
  [^String user
   ^String zone
   ^String folder-path
   ^ISeq   info-types
   ^String bad-chars
   ^ISeq   bad-names
   ^ISeq   bad-paths]
  (let [bad-file-cond   (q/mk-bad-file-cond folder-path bad-chars bad-names bad-paths)
        bad-folder-cond (q/mk-bad-folder-cond folder-path bad-chars bad-names bad-paths)
        query           (format (:count-bad-items-in-folder q/queries)
                                (q/mk-file-type-cond info-types)
                                 bad-file-cond
                                 bad-folder-cond)]
    (-> (run-query-string query user zone folder-path) first :total_filtered)))


(defn folder-permissions-for-user
  "Returns the highest permission value for the specified user on the folder."
  [user folder-path]
  (let [sorter (partial sort-by :access_type_id)]
    (-> (run-simple-query :folder-permissions-for-user user folder-path)
      sorter last :access_type_id)))

(defn file-permissions-for-user
  "Returns the highest permission value for the specified user on the file."
  [user file-path]
  (let [sorter   (partial sort-by :access_type_id)
        dirname  #(.getParent (file %))
        basename #(.getName (file %))]
    (-> (run-simple-query :file-permissions-for-user user (dirname file-path) (basename file-path))
      sorter last :access_type_id)))

(defn- add-permission
  [user {:keys [full_path type] :as item-map} ]
  (let [perm-func (if (= type "dataobject") file-permissions-for-user folder-permissions-for-user)]
    (assoc item-map :access_type_id (perm-func user full_path))))

(defn list-folders-in-folder
  "Returns a listing of the folders contained in the specified folder that the user has access to."
  [user zone folder-path]
  (map (partial add-permission user)
       (run-simple-query :list-folders-in-folder user zone folder-path)))

(def sort-columns
  {:type      "p.type"
   :modify-ts "p.modify_ts"
   :create-ts "p.create_ts"
   :data-size "p.data_size"
   :base-name "p.base_name"
   :full-path "p.full_path"})

(def sort-orders
  {:asc  "ASC"
   :desc "DESC"})


(defn ^ISeq folder-path-listing
  "Returns a complete folder listing for everything visible to a given user.

   Parameters:
     user        - the name of the user
     zone        - the authentication zone of the user
     folder-path - the absolute path to the folder

   Returns:
     It returns a sequence of paths."
  [^String user ^String zone ^String folder-path]
  (map :full_path (run-simple-query :folder-listing user zone folder-path)))


(defn- fmt-info-type
  [record]
  (if (and (= "dataobject" (:type record))
           (empty? (:info_type record)))
    (assoc record :info_type "raw")
    record))


(defn paged-folder-listing
  "Returns a page from a folder listing."
  [user zone folder-path sort-column sort-order limit offset & [file-types]]
  (if-not (contains? sort-columns sort-column)
    (throw (Exception. (str "Invalid sort-column " sort-column))))

  (if-not (contains? sort-orders sort-order)
    (throw (Exception. (str "Invalid sort-order " sort-order))))

  (let [ft-cond (q/mk-file-type-cond file-types)
        sc      (get sort-columns sort-column)
        so      (get sort-orders sort-order)
        query   (format (:paged-folder-listing q/queries) ft-cond sc so)]
    (map fmt-info-type (run-query-string query user zone folder-path limit offset))))


(defn select-files-with-uuids
  "Given a set of UUIDs, it returns a list of UUID-path pairs for each UUID that corresponds to a
   file."
  [uuids]
  ; This can't be run as a simple query.  I suspect the UUID db type is causing trouble
  (let [query (format (:select-files-with-uuids q/queries) (q/prepare-text-set uuids))]
    (run-query-string query)))

(defn select-folders-with-uuids
  "Given a set of UUIDs, it returns a list of UUID-path pairs for each UUID that corresponds to a
   folder."
  [uuids]
  ; This can't be run as a simple query. I suspect the UUID db type is causing trouble
  (let [query (format (:select-folders-with-uuids q/queries) (q/prepare-text-set uuids))]
    (run-query-string query)))

(defn paged-uuid-listing
  "Returns a page of filesystem entries corresponding to a list a set of UUIDs."
  [user zone sort-column sort-order limit offset uuids]
  (if-not (contains? sort-columns sort-column)
    (throw (Exception. (str "Invalid sort-column " sort-column))))

  (if-not (contains? sort-orders sort-order)
    (throw (Exception. (str "Invalid sort-order " sort-order))))

  (if (empty? uuids)
    []
    (let [sc    (get sort-columns sort-column)
          so    (get sort-orders sort-order)
          query (format (:paged-uuid-listing q/queries) (q/prepare-text-set uuids) sc so)
          p     (partial add-permission user)]
      (map p (run-query-string query user zone limit offset)))))
