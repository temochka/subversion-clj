;; ## Read-only Subversion access
;;
;; This code is extracted from <a href="http://beanstalkapp.com">beanstalkapp.com</a> caching daemon[1].
;;
;; Right now this is just a read-only wrapper around Java's SVNKit that allows you to look
;; into contents of local and remote repositories (no working copy needed). 
;; 
;; At this moment all this library can do is get unified information about all revisions or some particular revision
;; in the repo. However I'm planning to extend this code as Beanstalk uses more Clojure code
;; for performance critical parts
;;
;; [1] <a href="http://blog.beanstalkapp.com/post/23998022427/beanstalk-clojure-love-and-20x-better-performance">Post in Beanstalk's blog about this</a>
;;

(ns subversion-clj.core
  (:require 
    [clojure.string :as string]
    [subversion-clj.diffs :as diffs])
  (:use
    subversion-clj.utils)
  (:import 
     [org.tmatesoft.svn.core.internal.io.fs FSRepositoryFactory FSPathChange]
     [org.tmatesoft.svn.core.internal.io.dav DAVRepositoryFactory]
     [org.tmatesoft.svn.core.internal.io.svn SVNRepositoryFactoryImpl]
     [org.tmatesoft.svn.core.internal.util SVNHashMap SVNHashMap$TableEntry]     
     [org.tmatesoft.svn.core SVNURL SVNLogEntry SVNLogEntryPath SVNException]
     [org.tmatesoft.svn.core.io SVNRepository SVNRepositoryFactory]
     [org.tmatesoft.svn.core.wc SVNWCUtil SVNClientManager SVNRevision]
     [org.apache.commons.io.output NullOutputStream]
     [java.io File ByteArrayOutputStream]
     [java.util LinkedList]
     [subversion.diffs StructuredDiffGenerator]
     [org.tmatesoft.svn.core.wc.admin ISVNGNUDiffGenerator SVNLookClient]))

(declare log-record node-kind node-kind-at-rev)

(DAVRepositoryFactory/setup)
(SVNRepositoryFactoryImpl/setup)
(FSRepositoryFactory/setup)

(defn repo-for
  "Creates an instance of SVNRepository subclass from a legitimate Subversion URL like:
  
  * `https://wildbit.svn.beanstalkapp.com/somerepo`
  * `file:///storage/somerepo`
  * `svn://internal-server:3122/somerepo`

  You can use it like:

        (repo-for \"file:///storage/my-repo\")

  Or like this:

        (repo-for 
          \"https://wildbit.svn.beanstalkapp.com/repo\" 
          \"login\" 
          \"pass\")"
  (^SVNRepository [repo-path]
    (SVNRepositoryFactory/create (SVNURL/parseURIEncoded repo-path)))
  
  (^SVNRepository [repo-path name password]
    (let [repo (repo-for repo-path)
          auth-mgr (SVNWCUtil/createDefaultAuthenticationManager name password)]
      (.setAuthenticationManager repo auth-mgr)
      repo)))

(defn revisions-for 
  "Returns an array with all the revision records in the repository."
  [^SVNRepository repo]
  (->> (.log repo (string-array) (linked-list) 1 -1 true false)
    (map (partial log-record repo))
    (into [])))

(defn revision-for 
  "Returns an individual revision record.

   Example record for a copied directory:

        {:revision 6
        :author \"railsmonk\"
        :message \"copied directory\"
        :changes [[\"dir\" [\"new-dir\" \"old-dir\" 5] :copy]]}

   Example record for an edited files:

        {:revision 11
        :author \"railsmonk\"
        :message \"editing files\"
        :changes [[\"file\" \"commit1\" :edit]
                  [\"file\" \"commit3\" :edit]]}"
  [^SVNRepository repo ^Long revision]
  (let [revision (Long. revision)]
    (->> (.log repo (string-array) (linked-list) revision revision true false)
      first
      (log-record repo))))

(defn node-kind 
  "Returns kind of a node path at certain revision - file or directory."
  [repo path rev]
  (let [basename (.getName (File. ^String path))]
    (if (neg? (.indexOf basename "."))
      (let [node-kind-at-current-rev (node-kind-at-rev repo path rev)]
        (if (= "none" node-kind-at-current-rev)
          (node-kind-at-rev repo path (dec rev))
          node-kind-at-current-rev))
      "file")))

(defn- node-kind-at-rev ^String [^SVNRepository repo ^String path ^Long rev]
  (.. (.checkPath repo path rev) toString))

(def letter->change-sym 
  {\A :add
   \M :edit
   \D :delete
   \R :replace})

(defn- change-kind [^SVNLogEntryPath change-rec]
  (let [change-letter (.getType change-rec)
        copy-rev (.getCopyRevision change-rec)]
    (if (neg? copy-rev)
      (letter->change-sym change-letter)
      :copy)))

(defn- detailed-path 
  [repo rev log-record ^SVNHashMap$TableEntry path-record]
  (let [path (normalize-path (.getKey path-record))
        change-rec ^FSPathChange (.getValue path-record)
        node-kind (node-kind repo path rev)
        change-kind (change-kind change-rec)]
    (cond 
      (= change-kind :copy) [node-kind 
                             [path, (normalize-path (.getCopyPath change-rec)), (.getCopyRevision change-rec)] 
                             change-kind]
      :else [node-kind path change-kind])))

(defn- changed-paths [repo rev ^SVNLogEntry log-obj]
  (if (= rev (long 0))
    []
    (map (partial detailed-path repo rev log-record) ^SVNHashMap (.getChangedPaths log-obj))))

(defn- log-record [repo ^SVNLogEntry log-obj]
  (let [revision (.getRevision log-obj)
        message (.getMessage log-obj)
        paths (doall (changed-paths repo revision log-obj))]
    {:revision revision
     :author (.getAuthor log-obj)
     :time (.getDate log-obj)
     :message (if message (string/trim message) "")
     :changes paths}))

(defn- svnlook-client 
  ^SVNLookClient []
  (let [opts (SVNWCUtil/createDefaultOptions true)
        cm (SVNClientManager/newInstance opts)]
    (.getLookClient cm)))

(defn svn-revision
  "SVNRevision instance for a given revision number."
  ^SVNRevision [revision]
  (SVNRevision/create (long revision)))

(defn youngest
  "Youngest revision of a repository."
  ^Long [^SVNRepository repo]
  (.getLatestRevision repo))

(defn repo-dir
  "File instance for a repository directory."
  ^File [^SVNRepository repo]
  (File. (.. repo getLocation getPath)))

(defn diff-for 
  "File and property changes for a given revision. Returns a single ByteArrayOutputStream instance.

_Works only with repo object pointing to a local repo directory (not working copy)._"
  ([^SVNRepository repo revision]
    (let [output (baos)]
      (.doGetDiff (svnlook-client) (repo-dir repo) (svn-revision revision) true true true output)
      output))
  ([^SVNRepository repo revision ^ISVNGNUDiffGenerator generator]
    (let [cli (doto (svnlook-client) (.setDiffGenerator generator))]
      (.doGetDiff cli (repo-dir repo) (svn-revision revision) true true true null-stream))))

(defn structured-diff-for
  "File and property changes for a given revision, structured as maps of maps.

   Format of the returned map is:

         {:files 
            {\"dir-a/file1\" ByteArrayOutputStream
             \"dir-b/file2\" ByteArrayOutputStream}
          :properties 
            {\"dir-a/file1\" ByteArrayOutputStream}}
   
_Works only with repo object pointing to a local repo directory (not working copy)._"
  ([^SVNRepository repo revision]
    (let [generator (StructuredDiffGenerator.)]
      (diff-for repo revision generator)
      (.grabDiff generator))))
