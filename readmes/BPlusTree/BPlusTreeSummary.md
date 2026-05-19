# Proj2: B+ Tree Index

**Assignment spec:** [your-tasks.md](original-docs/your-tasks.md)

## Overview

A B+ tree is a balanced tree where inner nodes act as a routing layer (keys only) and leaf nodes store the actual data ((key, RecordId) pairs). All leaves are linked in a sorted singly-linked list, enabling efficient range scans without revisiting the tree. This gives O(log N) point lookups and O(log N + K) range scans for K results.

This implementation is a **persistent** B+ tree: every node is serialized to a page managed by a `BufferManager`. The tree survives across sessions by deserializing nodes from disk on demand.

---

## Caveats & Limitations

Several production concerns are intentionally omitted:

| Omitted Feature | What a Real DB Would Do |
|-----------------|-------------------------|
| **No rebalancing on delete** | Merge underflowing nodes with siblings or borrow entries. PostgreSQL uses VACUUM for lazy cleanup. |
| **No duplicate key support** | Use (key, RID) composite keys or per-key overflow buckets. |
| **No node-level locking** | `LockContext` coarse-locks the whole tree (S/X). Production trees use latch crabbing (lock-coupling) for fine-grained concurrency. |
| **No crash recovery** | `sync()` writes to the buffer pool but splits are not atomic. A real system would use a write-ahead log (WAL) for redo/undo on crash. |
| **No phantom prevention** | `BPlusTreeIterator` does not handle concurrent insertions into the scan range. |
| **Static order** | Order `d` is fixed at tree creation from the page size. |

---

## Project Files

| File | Role |
|------|------|
| `BPlusTree.java` | Public API entry point; owns the root node reference |
| `InnerNode.java` | Routing nodes; store keys and child page-number pointers |
| `LeafNode.java` | Data nodes; store (key, RecordId) pairs and a rightSibling page pointer |
| `BPlusNode.java` | Abstract base class for `InnerNode` / `LeafNode`; dispatches `fromBytes` |

---

## On-Disk Serialization

Each node occupies exactly one page. The first byte is a type tag (`0` = inner, `1` = leaf) that `BPlusNode.fromBytes` uses to dispatch to the correct subclass.

**LeafNode page layout:**
```
[1B: isLeaf=1] [8B: rightSiblingPageNum | -1 if none] [4B: n] [n × (keySize + ridSize): (key, rid) pairs]
```

**InnerNode page layout:**
```
[1B: isLeaf=0] [4B: n] [n × keySize: keys] [(n+1) × 8B: child page nums]
```

Max order `d` is derived from page size at tree creation:
- Leaf: `d = (pageSize - 13) / (keySize + ridSize) / 2`
- Inner: `d = (pageSize - 13) / (keySize + 8) / 2`

`BPlusTree.maxOrder` takes the minimum of the two so every node type fits on one page.

### `sync()`

Every mutating operation ends with `sync()`. It serializes the node to bytes, compares against the current page content, and only writes on a diff — avoiding unnecessary buffer pool traffic.

---

## Task 1 — `LeafNode::fromBytes`

Deserializes a page buffer back into a `LeafNode`. It is the exact inverse of `toBytes`:

```
buf.get()     → isLeaf type tag (assert == 1)
buf.getLong() → rightSiblingPageNum (-1L → Optional.empty())
buf.getInt()  → n (number of entries)
loop n times:
  DataBox.fromBytes(buf, keySchema) → key
  RecordId.fromBytes(buf)           → rid
```

---

## Task 2 — Core Operations

### `get` / `getLeftmostLeaf`

Both follow the tree's recursive structure top-down:

- **LeafNode**: base cases — both return `this`.
- **InnerNode.get**: calls `upperBound(key, keys)` to find the index of the first key strictly greater than `key`. That index is the correct child to recurse into. This works because the B+ tree invariant places records with key `k` in the subtree at child index `i` where `keys[i-1] <= k < keys[i]`.
- **InnerNode.getLeftmostLeaf**: repeatedly follows `children.get(0)` until hitting a `LeafNode`.
- **BPlusTree.get**: calls `root.get(key)` to reach the correct leaf, then does a final binary search within the leaf's key list to retrieve the `RecordId`.

### `put` — Insertion with Splitting

The split protocol: `put` returns `Optional<Pair<DataBox, Long>>`. `Optional.empty()` means no split. A non-empty value carries `(pushUpKey, newRightChildPageNum)` that the caller must absorb.

**LeafNode.put:**
1. `upperBound(key, keys)` finds the insertion index. Throw `BPlusTreeException` if the key already exists.
2. Insert (key, rid) in sorted order. If `keys.size() <= 2d`: `sync()` and return empty.
3. Overflow: create a right sibling with the upper half `[d, 2d]`, keep the lower half `[0, d)`, update `rightSibling` pointer. Return `(rightSibling.keys[0], rightSiblingPageNum)`. The boundary key is **copied up** — it remains in the right leaf.

**InnerNode.put:**
1. Recurse into `getChild(upperBound(key, keys))`.
2. If the child split, insert `(splitKey, rightChildPageNum)` into own keys/children at the correct sorted position.
3. If `children.size() <= 2d+1`: `sync()` and return empty.
4. Overflow: the middle key at index `d` is **pushed up** — it is removed from this node. Right sibling gets keys `[d+1, 2d]` and children `[d+1, 2d+1]`. Left keeps keys `[0, d)` and children `[0, d]`. Return `(keys[d], rightInnerPageNum)`.

**BPlusTree.put:** If the root splits, a new `InnerNode` root is created holding the pushed-up key with two children (old root page + new right page). `updateRoot` persists the new root page number to metadata.

> **Key distinction:** Leaf splits **copy** the boundary key up (it stays in the right leaf). Inner splits **push** the middle key up (it leaves both children and lives only in the parent).

### `remove`

No rebalancing — this implementation intentionally omits sibling borrowing/merging.

- **LeafNode.remove**: locate the key with `upperBound`, remove (key, rid) pair, `sync()`.
- **InnerNode.remove**: route to the correct child via `upperBound`, recurse, `sync()`.

The routing key in an ancestor may now be a "ghost" pointing to an underflowing subtree, but the tree remains correct for lookups and scans.

---

## Task 3 — Scans (`BPlusTreeIterator`)

The iterator lazily traverses the leaf level — it never materializes all record IDs in memory.

**`scanAll()`** — seeds `BPlusTreeIterator` at `root.getLeftmostLeaf()`, index 0.

**`scanGreaterEqual(key)`** — navigates to the leaf containing `key` via `root.get(key)`, finds the starting index (first `i` where `keys[i] >= key`), seeds `BPlusTreeIterator(leaf, index)`.

**`BPlusTreeIterator.next()`:**
1. Return `leaf.getRids().get(leafIndex)`.
2. Advance `leafIndex`. If it reaches the end of the current leaf, follow `rightSibling` to the next leaf and reset `leafIndex` to 0.
3. `hasNext()` checks if still within current leaf or if a right sibling exists.

This works because every leaf's `rightSibling` pointer forms a singly-linked list sorted in key order.

---

## Task 4 — Bulk Load

Builds a fresh index from a pre-sorted, duplicate-free data stream. More efficient than repeated `put` calls because it always appends to the rightmost path.

**Fill factor** applies to leaves only: a leaf fills to `ceil(2d × fillFactor)` entries before splitting. Inner nodes still split at exactly `2d` keys.

**LeafNode.bulkLoad:** Append from the iterator until `keys.size() > threshold`. On overflow, split exactly like `put`.

**InnerNode.bulkLoad:** Always recurse into the **rightmost child** (`children.get(children.size()-1)`) — all new data goes right. When the child splits, absorb the pushed-up key. When self overflows, split like `put` and return to parent.

**BPlusTree.bulkLoad:** Loops: call `root.bulkLoad(data, fillFactor)`. When the root splits, create a new `InnerNode` root (identical to `put`). Continue until the data iterator is exhausted.

For a step-by-step visual walkthrough see [images/BPlusTreeVisualizations.pdf](../../images/BPlusTreeVisualizations.pdf).

Precondition: the tree must be empty at call time. Enforced explicitly:
```java
if (!(root instanceof LeafNode) || !((LeafNode) root).getKeys().isEmpty())
    throw new BPlusTreeException("bulkLoad requires an empty tree");
```

---

## Key Design Decisions

| Decision | Detail |
|----------|--------|
| No duplicate keys | `put` throws `BPlusTreeException` on duplicate insertion |
| No rebalancing on delete | Nodes can fall below minimum occupancy; tree height never shrinks |
| Copy-up vs push-up | Leaf splits copy the boundary key up; inner splits push the middle key up |
| `sync()` on every mutation | Must be called after every structural change to persist to the buffer pool |
| Lazy scan iterator | `BPlusTreeIterator` follows the leaf linked-list; never materializes all records |
| Order derived from page size | Fixed at tree creation; `maxOrder` ensures both node types fit on one page |

---

## Running Tests

Tests are in `src/test/java/edu/berkeley/cs186/database/index/`.

```bash
# Task 1 — fromBytes
mvn test -Dtest="TestLeafNode#testToAndFromBytes" -DfailIfNoTests=false

# Task 2 — core operations (get, put, remove)
mvn test -Dtest="TestLeafNode,TestInnerNode,TestBPlusTree" -DfailIfNoTests=false

# Task 3 — scans
mvn test -Dtest="TestBPlusTree#testRandomPuts" -DfailIfNoTests=false

# Task 4 + full suite (all index tests must pass)
mvn test -Dtest="edu.berkeley.cs186.database.index.*" -DfailIfNoTests=false
```

Recommended implementation order: `fromBytes` → `get` / `getLeftmostLeaf` → `put` → `remove` → scans → `bulkLoad`.
