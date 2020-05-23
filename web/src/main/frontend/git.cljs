(ns frontend.git
  (:refer-clojure :exclude [clone merge])
  (:require [promesa.core :as p]
            [frontend.util :as util]
            [clojure.string :as string]
            [clojure.set :as set]
            [frontend.state :as state]
            [goog.object :as gobj]
            [cljs-bean.core :as bean]
            ["/frontend/git_ext" :as git-ext]))

;; TODO: move to a js worker

(defonce default-branch "master")
;; only support Github now
(defn auth
  [token]
  {:username (get-in @state/state [:me :name])
   :token token})

(defn set-username-email
  [dir username email]
  (util/p-handle (js/git.config (clj->js
                                 {:global true
                                  :dir dir
                                  :path "user.name"
                                  :value username}))
                 (fn [result]
                   (js/git.config (clj->js
                                   {:global true
                                    :dir dir
                                    :path "user.email"
                                    :value email})))
                 (fn [error]
                   (prn "error:" error))))

(defn with-auth
  [token m]
  (clj->js
   (clojure.core/merge (auth token)
                       m)))

(defn clone
  [repo-url token]
  (js/git.clone (with-auth token
                  {:dir (util/get-repo-dir repo-url)
                   :url repo-url
                   :corsProxy "https://cors.isomorphic-git.org"
                   :singleBranch true
                   :depth 1})))

(defn list-files
  [repo-url]
  (js/git.listFiles (clj->js
                     {:dir (util/get-repo-dir repo-url)
                      :ref "HEAD"})))

(defn fetch
  [repo-url token]
  (js/git.fetch (with-auth token
                  {:dir (util/get-repo-dir repo-url)
                   :ref default-branch
                   :singleBranch true
                   :depth 100
                   :tags false})))

(defn merge
  [repo-url]
  (js/git.merge (clj->js
                 {:dir (util/get-repo-dir repo-url)
                  :ours default-branch
                  :theirs (str "remotes/origin/" default-branch)
                  :fastForwardOnly true})))

(defn checkout
  [repo-url]
  (js/git.checkout (clj->js
                    {:dir (util/get-repo-dir repo-url)
                     :ref default-branch})))

(defn log
  [repo-url token depth]
  (js/git.log (with-auth token
                {:dir (util/get-repo-dir repo-url)
                 :ref default-branch
                 :depth depth
                 :singleBranch true})))

(defn pull
  [repo-url token]
  (js/git.pull (with-auth token
                 {:dir (util/get-repo-dir repo-url)
                  :ref default-branch
                  :singleBranch true
                  :fast true})))
(defn add
  [repo-url file]
  (js/git.add (clj->js
               {:dir (util/get-repo-dir repo-url)
                :filepath file})))

(defn commit
  [repo-url message]
  (let [{:keys [name email]} (:me @state/state)]
    (js/git.commit (clj->js
                    {:dir (util/get-repo-dir repo-url)
                     :message message
                     :author {:name name
                              :email email}}))))

(defn read-commit
  [repo-url oid]
  (js/git.readCommit (clj->js
                      {:dir (util/get-repo-dir repo-url)
                       :oid oid})))

(defn push
  ([repo-url token]
   (push repo-url token false))
  ([repo-url token force?]
   (js/git.push (with-auth token
                  {:dir (util/get-repo-dir repo-url)
                   :remote "origin"
                   :ref default-branch
                   :force force?}))))

(defn add-commit
  [repo-url file message commit-ok-handler commit-error-handler]
  (util/p-handle
   (add repo-url file)
   (fn [_]
     (util/p-handle
      (commit repo-url message)
      (fn []
        (commit-ok-handler))
      (fn [error]
        (commit-error-handler error))))))

(defn get-diffs
  [repo-url hash-1 hash-2]
  (let [dir (util/get-repo-dir repo-url)]
    (p/let [diffs (git-ext/getFileStateChanges hash-1 hash-2 dir)
            diffs (cljs-bean.core/->clj diffs)
            diffs (remove #(= (:type %) "equal") diffs)
            diffs (map (fn [diff]
                         (update diff :path #(subs % 1))) diffs)]
      diffs)))

;; https://isomorphic-git.org/docs/en/statusMatrix
;; TODO: status should not be `pulling`, otherwise the `:deleted` part is weird.
(defn get-status-matrix
  ([repo-url]
   (get-status-matrix repo-url "master"))
  ([repo-url branch]
   (p/let [matrix (js/git.statusMatrix
                   (clj->js
                    {:dir (util/get-repo-dir repo-url)
                     :ref "HEAD"}))]
     (let [matrix (bean/->clj matrix)]
       ;; added, modified, deleted
       {:added (->> (filter (fn [[_file head-status _workdir-status _stage-status]]
                              (= head-status 0))
                            matrix)
                    (map first))
        :modified (->> (filter (fn [[_file _head-status workdir-status _stage-status]]
                                 (= workdir-status 2))
                               matrix)
                       (map first))
        :deleted (->> (filter (fn [[_file _head-status workdir-status _stage-status]]
                                (= workdir-status 0))
                              matrix)
                      (map first))}))))

(defn find-common-base
  ([repo-url remote-id local-id]
   (find-common-base repo-url remote-id local-id (atom [local-id]) (atom [remote-id])))
  ([repo-url remote-id local-id local-commits remote-commits]
   ;; FIXME: p/plet not working
   (p/let
       [local-commit (read-commit repo-url local-id)]
     (p/let [remote-commit (read-commit repo-url remote-id)]
       (let [local-parent (first (get-in (bean/->clj local-commit) [:commit :parent]))

             remote-parent (first (get-in (bean/->clj remote-commit) [:commit :parent]))]
         (swap! local-commits conj local-parent)
         (swap! remote-commits conj remote-parent)
         (let [commons (set/intersection (set @local-commits)
                                         (set @remote-commits))]
           (if (seq commons)
             (first commons)
             (find-common-base repo-url local-parent remote-parent local-commits remote-commits))))))))

(defn get-local-diffs
  [repo-url remote-id local-id]
  (get-diffs repo-url remote-id local-id))

(defn read-blob
  [repo-url oid path]
  (js/git.readBlob (clj->js
                    {:dir (util/get-repo-dir repo-url)
                     :gitdir (str (util/get-repo-dir repo-url) ".git")
                     :oid oid
                     :path path})))

;; * await git.writeRef({
;;                       *   fs,
;;                       *   dir: '/tutorial',
;;                       *   ref: 'refs/heads/another-branch',
;;                       *   value: 'HEAD'
;;                       * })
(defn write-ref!
  [repo-url oid]
  (js/git.writeRef (clj->js
                    {:dir (util/get-repo-dir repo-url)
                     :ref (str "refs/heads/" default-branch)
                     :value oid
                     :force true})))