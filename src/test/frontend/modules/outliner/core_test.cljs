(ns frontend.modules.outliner.core-test
  (:require [cljs.test :refer [deftest is are testing use-fixtures run-tests] :as test]
            [frontend.modules.outliner.tree :as tree]
            [datascript.core :as d]
            [frontend.react :as r]
            [frontend.modules.outliner.utils :as outliner-u]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.fixtures :as fixtures]
            [cljs-run-test :refer [run-test]]
            [frontend.core-test :as core-test]
            [frontend.handler.block :as block]))

(use-fixtures :each
  fixtures/react-impl
  fixtures/react-components
  fixtures/reset-db)

(defn build-block
  ([id]
   (build-block id nil nil))
  ([id parent-id left-id & [m]]
   (let [m (->> (merge m {:block/uuid id
                          :block/parent
                          (outliner-u/->block-lookup-ref parent-id)
                          :block/left
                          (outliner-u/->block-lookup-ref left-id)
                          :block/content (str id)})
             (remove #(nil? (val %)))
             (into {}))]
     (outliner-core/block m))))

(defrecord TreeNode [id children])

(defn build-node-tree
  [[id children :as _tree]]
  (let [children (mapv build-node-tree children)]
    (->TreeNode id children)))

(defn build-db-records
  "build RDS record from memory node struct."
  [tree-record]
  (letfn [(build [node queue]
            (let [{:keys [id left parent]} node
                  block (build-block id parent left)
                  left (atom (:id node))
                  children (map (fn [c]
                                  (let [node (assoc c :left @left :parent (:id node))]
                                    (swap! left (constantly (:id c)))
                                    node))
                             (:children node))
                  queue (concat queue children)]
              (tree/-save block)
              (when (seq queue)
                (build (first queue) (rest queue)))))]
    (let [root (assoc tree-record :left "1" :parent "1")]
      (tree/-save (build-block "1"))
      (build root '()))))


(def tree [1 [[2 [[3 [[4]
                      [5]]]
                  [6 [[7 [[8]]]]]
                  [9 [[10]
                      [11]]]]]
              [12 [[13]
                   [14]
                   [15]]]
              [16 [[17]]]]])

(def node-tree (build-node-tree tree))

(comment
  (build-db-records node-tree)
  (dotimes [i 18]
    (when-not (= i 0)
      (prn (d/pull @(core-test/get-current-conn) '[*] [:block/uuid i])))))

(deftest test-insert-node-as-first-child
  "
  Inert a node between 6 and 9.
  [1 [[2 [[18]         ;; add
          [3 [[4]
              [5]]]
          [6 [[7 [[8]]]]]

          [9 [[10]
              [11]]]]]
      [12 [[13]
           [14]
           [15]]]
      [16 [[17]]]]]
   "
  (build-db-records node-tree)
  (let [new-node (build-block 18 nil nil)
        parent-node (build-block 2 1 1)]
    (outliner-core/insert-node-as-first-child new-node parent-node)
    (let [children-of-2 (->> (build-block 2 1 1)
                          (tree/-get-children)
                          (mapv #(-> % :data :block/uuid)))]
      (is (= [18 3 6 9] children-of-2)))))

(deftest test-insert-node-as-sibling
  "
  Inert a node between 6 and 9.
  [1 [[2 [[3 [[4]
              [5]]]
          [6 [[7 [[8]]]]]
          [18]         ;; add
          [9 [[10]
              [11]]]]]
      [12 [[13]
           [14]
           [15]]]
      [16 [[17]]]]]
   "
  (build-db-records node-tree)
  (let [new-node (build-block 18 nil nil)
        left-node (build-block 6 2 3)]
    (outliner-core/insert-node-as-sibling new-node left-node)
    (let [children-of-2 (->> (build-block 2 1 1)
                          (tree/-get-children)
                          (mapv #(-> % :data :block/uuid)))]
      (is (= [3 6 18 9] children-of-2)))))

(deftest test-delete-node
  "
  Inert a node between 6 and 9.
  [1 [[2 [[3 [[4]
              [5]]]
          [6 [[7 [[8]]]]]  ;; delete 6
          [9 [[10]
              [11]]]]]
      [12 [[13]
           [14]
           [15]]]
      [16 [[17]]]]]
   "
  (build-db-records node-tree)
  (let [node (build-block 6 2 3)]
    (outliner-core/delete-node* node)
    (let [children-of-2 (->> (build-block 2 1 1)
                          (tree/-get-children)
                          (mapv #(-> % :data :block/uuid)))]
      (is (= [3 9] children-of-2)))))


(deftest test-move-subtree-as-sibling
  "
  Move 3 between 14 and 15.
  [1 [[2 [[6 [[7 [[8]]]]]
          [9 [[10]
              [11]]]]]
      [12 [[13]
           [14]
           [3 [[4]    ;; moved 3
               [5]]]
           [15]]]
      [16 [[17]]]]]
   "
  (build-db-records node-tree)
  (let [node (build-block 3 2 2)
        target-node (build-block 14 12 13)]
    (outliner-core/move-subtree* node target-node true)
    (let [old-parent's-children (->> (build-block 2 1 1)
                                  (tree/-get-children)
                                  (mapv #(-> % :data :block/uuid)))
          new-parent's-children (->> (build-block 12 1 2)
                                  (tree/-get-children)
                                  (mapv #(-> % :data :block/uuid)))]
      (is (= [6 9] old-parent's-children))
      (is (= [13 14 3 15] new-parent's-children)))))

(deftest test-move-subtree-as-first-child
  "
  Move 3 as first child of 12.

  [1 [[2 [[6 [[7 [[8]]]]]
          [9 [[10]
              [11]]]]]
      [12 [[3 [[4]    ;; moved 3
               [5]]]
           [13]
           [14]
           [15]]]
      [16 [[17]]]]]
   "
  (build-db-records node-tree)
  (let [node (build-block 3 2 2)
        target-node (build-block 12 1 2)]
    (outliner-core/move-subtree* node target-node false)
    (let [old-parent's-children (->> (build-block 2 1 1)
                                  (tree/-get-children)
                                  (mapv #(-> % :data :block/uuid)))
          new-parent's-children (->> (build-block 12 1 2)
                                  (tree/-get-children)
                                  (mapv #(-> % :data :block/uuid)))]
      (is (= [6 9] old-parent's-children))
      (is (= [3 13 14 15] new-parent's-children)))))

;
;(r/defc render-react-tree
;  [root]
;  (let [children (tree/-get-children root)]
;    (if (seq children)
;      [(tree/-get-id root)
;       (mapv (fn [child]
;               @(->> (render-react-tree child)
;                  (r/with-key (str "root-" (tree/-get-id child)))))
;         children)]
;      [(tree/-get-id root)])))
;
;(deftest test-react-insert-node-as-first-child
;  "
;  [1 [[2 [[3 [[4]
;              [5]]]
;          [6 [[7 [[8]]]]]
;          [9 [[10]
;              [11]]]]]
;      [12 [[13]
;           [14]
;           [15]]]
;      [16 [[17]]]]]
;      "
;  (build-db-records node-tree)
;  (let [root (build-block 1 nil nil)
;        result (->> (render-react-tree root)
;                 (r/with-key (str "root-" (tree/-get-id root))))]
;    (is (= [1 [[2 [[3 [[4]
;                       [5]]]
;                   [6 [[7 [[8]]]]]
;                   [9 [[10]
;                       [11]]]]]
;               [12 [[13]
;                    [14]
;                    [15]]]
;               [16 [[17]]]]]
;          @result))
;    (let [new-node (build-block 18 nil nil)
;          parent-node (build-block 2 1 1)]
;      (outliner-core/insert-node-as-first-child new-node parent-node)
;      (is (= [1 [[2 [[18]
;                     [3 [[4]
;                         [5]]]
;                     [6 [[7 [[8]]]]]
;                     [9 [[10]
;                         [11]]]]]
;                 [12 [[13]
;                      [14]
;                      [15]]]
;                 [16 [[17]]]]]
;            @result)))))
;
;(deftest test-react-for-insert-node-as-sibling
;  "
;  [1 [[2 [[3 [[4]
;              [5]]]
;          [6 [[7 [[8]]]]]
;          [9 [[10]
;              [11]]]]]
;      [12 [[13]
;           [14]
;           [15]]]
;      [16 [[17]]]]]
;      "
;  (build-db-records node-tree)
;  (let [root (build-block 1 nil nil)
;        result (->> (render-react-tree root)
;                 (r/with-key (str "root-" (tree/-get-id root))))]
;    (is (= [1 [[2 [[3 [[4]
;                       [5]]]
;                   [6 [[7 [[8]]]]]
;                   [9 [[10]
;                       [11]]]]]
;               [12 [[13]
;                    [14]
;                    [15]]]
;               [16 [[17]]]]]
;          @result))
;    (let [new-node (build-block 18 nil nil)
;          left-node (build-block 3 2 2)]
;      (outliner-core/insert-node-as-sibling new-node left-node)
;      (is (= [1 [[2 [[3 [[4]
;                         [5]]]
;                     [18]
;                     [6 [[7 [[8]]]]]
;                     [9 [[10]
;                         [11]]]]]
;                 [12 [[13]
;                      [14]
;                      [15]]]
;                 [16 [[17]]]]]
;            @result)))))
;
;(deftest test-react-for-delete-node
;  "
;  [1 [[2 [[3 [[4]
;              [5]]]
;          [6 [[7 [[8]]]]]
;          [9 [[10]
;              [11]]]]]
;      [12 [[13]
;           [14]
;           [15]]]
;      [16 [[17]]]]]
;      "
;  (build-db-records node-tree)
;  (let [root (build-block 1 nil nil)
;        result (->> (render-react-tree root)
;                 (r/with-key (str "root-" (tree/-get-id root))))]
;    (is (= [1 [[2 [[3 [[4]
;                       [5]]]
;                   [6 [[7 [[8]]]]]
;                   [9 [[10]
;                       [11]]]]]
;               [12 [[13]
;                    [14]
;                    [15]]]
;               [16 [[17]]]]]
;          @result))
;    (let [node (build-block 6 2 3)]
;      (outliner-core/delete-node node)
;      (is (= [1 [[2 [[3 [[4]
;                         [5]]]
;                     [9 [[10]
;                         [11]]]]]
;                 [12 [[13]
;                      [14]
;                      [15]]]
;                 [16 [[17]]]]]
;            @result)))))
;
;(deftest test-react-for-move-subtree
;  "
;  [1 [[2 [[3 [[4]
;              [5]]]
;          [6 [[7 [[8]]]]]
;          [9 [[10]
;              [11]]]]]
;      [12 [[13]
;           [14]
;           [15]]]
;      [16 [[17]]]]]
;      "
;  (build-db-records node-tree)
;  (let [root (build-block 1 nil nil)
;        result (->> (render-react-tree root)
;                 (r/with-key (str "root-" (tree/-get-id root))))]
;    (is (= [1 [[2 [[3 [[4]
;                       [5]]]
;                   [6 [[7 [[8]]]]]
;                   [9 [[10]
;                       [11]]]]]
;               [12 [[13]
;                    [14]
;                    [15]]]
;               [16 [[17]]]]]
;          @result))
;    (let [node (build-block 3 2 2)
;          new-parent (build-block 12 1 2)
;          new-left (build-block 14 12 13)]
;      (outliner-core/move-subtree node new-parent new-left)
;      (is (= [1 [[2 [[6 [[7 [[8]]]]]
;                     [9 [[10]
;                         [11]]]]]
;                 [12 [[13]
;                      [14]
;                      [3 [[4]
;                          [5]]]
;                      [15]]]
;                 [16 [[17]]]]]
;            @result)))))