# Proj3 Part 1: Join Algorithms

## Overview

This part implements four join algorithms used by RookieDB's query engine. All operators follow the **volcano model**: each operator exposes an `Iterator<Record>` and pulls records lazily from its children on demand. Join operators sit atop two child operators (left and right sources) and emit joined records one at a time.

The four algorithms covered are:

| Algorithm | When it wins |
|-----------|-------------|
| **BNLJ** | General-purpose; better than SNLJ by loading a full block of left pages at once |
| **Sort** | Prerequisite for SMJ; also useful standalone when sorted output is needed |
| **Sort-Merge Join** | Best when both inputs are already sorted or nearly sorted |
| **Grace Hash Join** | Best when inputs are large and unsorted; handles cases where SHJ fails |

---

## Caveats & Limitations

| Omitted Feature | What a Real DB Would Do |
|-----------------|-------------------------|
| **No adaptive joins** | Real optimizers switch strategies mid-query based on runtime cardinality |
| **GHJ never wins the optimizer** | `estimateIOCost()` returns `Integer.MAX_VALUE` to steer the optimizer away; GHJ can still fail if hash skew forces too many recursive passes |
| **SHJ is fragile** | Simple Hash Join requires the smaller relation to fit entirely in B pages; GHJ is the robust alternative |
| **SMJ cost is 0** | `SortMergeOperator.estimateIOCost()` returns 0 — cost is already accounted for in the child `SortOperator` nodes |
| **External sort is strictly two-phase** | `sort()` does a fixed number of merge passes computed upfront; no dynamic adjustment |

---

## Project Files

| File | Role |
|------|------|
| `query/join/BNLJOperator.java` | Block Nested Loop Join; inner `BNLJIterator` manages block/page iteration |
| `query/SortOperator.java` | External merge sort; produces a single sorted `Run` on disk |
| `query/join/SortMergeOperator.java` | Sort-Merge Join; wraps sources in `SortOperator` if not already sorted |
| `query/join/GHJOperator.java` | Grace Hash Join; partitions both relations recursively, then build-and-probe |
| `query/join/SHJOperator.java` | Simple Hash Join (provided); GHJ generalizes this |
| `query/join/SNLJOperator.java` | Simple Nested Loop Join (provided); BNLJ generalizes this |
| `query/disk/Run.java` | Append-only on-disk sequence of records; used by sort and GHJ |
| `query/disk/Partition.java` | Like `Run`, but used to hold hash partitions during GHJ |
| `common/iterator/BacktrackingIterator.java` | Iterator with `markNext()` / `reset()` for revisiting records |

---

## BacktrackingIterator

`BacktrackingIterator<T>` extends `Iterator<T>` with two extra methods:

- `markNext()` — marks the position of the next record to be returned (i.e., the one `next()` would give you)
- `reset()` — rewinds the iterator back to the last marked position

This is the primitive that makes both BNLJ and SMJ work without re-reading the source from disk:

- **BNLJ**: `rightPageIterator` is marked at the start of each right page so that when the left block advances, the right page can be replayed. `rightSourceIterator` is marked at the very beginning so the entire right relation can be rescanned for each new left block.
- **SMJ**: `rightIterator` is marked at the start of each group of equal keys. When a left record advances past the group, `reset()` replays that group for the next left record with the same key.

The rule: **always call `markNext()` before the first `next()`**, otherwise `reset()` has no valid position to return to.

---

## Algorithm Details

### Block Nested Loop Join (BNLJ)

BNLJ is a chunk-based refinement of SNLJ. Instead of loading one left record at a time, it loads **B-2 pages** of left records per outer iteration, scanning the entire right relation once per chunk.

**Loop structure (inside-out):**
```
for (chunk of B-2 left pages)
    for (page of right relation)
        for (record in left chunk)
            for (record in right page)
                if keys match → emit joined record
```

**`fetchNextRecord` state machine:**
1. `rightPageIterator.hasNext()` → compare current `leftRecord` with next right record; emit on match
2. `leftBlockIterator.hasNext()` → advance `leftRecord`, reset `rightPageIterator` to page start
3. `rightSourceIterator.hasNext()` → fetch next right page, reset `leftBlockIterator`, re-seat `leftRecord`
4. `leftSourceIterator.hasNext()` → fetch next left block, reset `rightSourceIterator`, fetch first right page
5. otherwise → return `null` (exhausted)

**IO cost:** `ceil(|R| / (B-2)) × |S| + |R|`

---

### External Sort (`SortOperator`)

A standard two-phase external merge sort (more passes if needed).

**Pass 0 — `sortRun`:** Read B pages at a time, sort in memory using Java's `List.sort`, write each sorted chunk as a `Run` to disk.

**Merge passes — `mergeSortedRuns` / `mergePass`:**
- `mergeSortedRuns`: merges up to B-1 runs using a min-heap (`PriorityQueue<Pair<Record, Integer>>`). The pair stores `(record, runIndex)` so the heap always knows which run to pull from next.
- `mergePass`: groups the current list of runs into batches of B-1 and calls `mergeSortedRuns` on each batch.
- `sort`: runs pass 0, then computes `ceil(log_{B-1}(ceil(N/B)))` merge passes and loops.

**IO cost:** `2N × (1 + ceil(log_{B-1}(ceil(N/B)))) + source cost`

---

### Sort-Merge Join (`SortMergeOperator`)

Sorts both inputs (if not already sorted) then merges them in a single scan.

**Preparation:**
- Left: wrapped in `SortOperator` unless already `sortedBy` the join column.
- Right: same, but if already sorted and not materialized, wrapped in `MaterializeOperator` (right side needs backtracking).

**`fetchNextRecord` logic:**
1. **Advance phase** (`!marked`): advance left past records smaller than right, then advance right past records smaller than left, marking the right position each time equality is possible.
2. **Emit phase** (`marked = true`): while `leftRecord == rightRecord`, emit joined pairs and advance right.
3. **Reset phase**: when right runs out of matching records, `reset()` right back to the mark, advance left, set `marked = false`, and re-enter the advance phase.

> The mark always sits at the first record of a group of equal right-side keys, so a left record with a duplicate can replay the entire matching right group.

---

### Grace Hash Join (`GHJOperator`)

Handles inputs too large for SHJ by partitioning both relations with the same hash function before joining.

**`partition(partitions, records, left, pass)`:** Hashes the join column of each record with `HashFunc.hashDataBox(value, pass)` and routes it to `partitions[hash % (B-1)]`. Using `pass` as a seed ensures different hash functions across recursive levels.

**`buildAndProbe(leftPartition, rightPartition)`:** Picks the smaller partition (by page count) as the build side. Builds an in-memory `HashMap<DataBox, List<Record>>`, then scans the probe side and emits matches. The smaller side must fit in B-2 pages.

**`run(leftRecords, rightRecords, pass)`:**
1. Create B-1 partitions for each side, partition all records.
2. For each partition pair: if `min(|leftPart|, |rightPart|) <= B-2` → `buildAndProbe`. Otherwise recurse with `pass + 1`.
3. Throws after 5 passes (safety valve against infinite hash skew).


## Key Design Decisions

| Decision | Detail |
|----------|--------|
| Right source always materialized in BNLJ | `BNLJOperator` constructor calls `materialize(rightSource, transaction)` so the right side can be reset cheaply |
| GHJ accumulates results in a `Run` | The full result is written to disk before iteration begins; `backtrackingIterator()` triggers the join on first call |
| SMJ skips a sort if already sorted | `prepareLeft/Right` checks `sortedBy()` to avoid redundant sort passes |
| `markNext()` before first `next()` | Both BNLJ and SMJ call `markNext()` on the right iterator immediately so `reset()` is always valid |
| Pass index as hash seed in GHJ | Passing `pass` to `HashFunc.hashDataBox` prevents the same hash collisions from recurring every level |
| Leaf splits copy-up; inner splits push-up | *(B+ tree concept for reference)* — analogously, BNLJ copies the left block into memory while SMJ "pushes" the sort cost to a child operator |

---

## Reference: Provided (Unused) Algorithms

These algorithms ship with the skeleton code and are not selected by the optimizer, but BNLJ and GHJ are direct generalizations of them — reading them first is the fastest way to understand the patterns.

### Simple Nested Loop Join (`SNLJOperator`)

The baseline join. For every left record, scan the entire right relation and emit matches. No blocking, no buffering.

```
for (record r in R):
    for (record s in S):
        if r[key] == s[key] → emit r ⋈ s
```

IO cost: `|R_records| × |S_pages| + |R|` — catastrophic on large inputs. BNLJ amortizes the right scan cost by loading a full block of left pages before each right scan.

### Simple Hash Join (`SHJOperator`)

A single-pass hash join. Partitions only the **left** relation into B-1 buckets, then probes each bucket with the full right relation. Requires every left partition to fit in B-2 pages — effectively `|R| < B × pageSize`. Throws `IllegalArgumentException` on overflow.

```
partition R into B-1 buckets (left only, pass=1)
for each bucket:
    build hash table from bucket
    probe with all of S
```

GHJ removes the size constraint by partitioning **both** sides and recursing on buckets that are still too large.

Tests are in `src/test/java/edu/berkeley/cs186/database/query/`.

```bash
# BNLJ
mvn test -Dtest="TestBNLJOperator" -DfailIfNoTests=false

# External Sort
mvn test -Dtest="TestSortOperator" -DfailIfNoTests=false

# Sort-Merge Join
mvn test -Dtest="TestSortMergeOperator" -DfailIfNoTests=false

# Grace Hash Join
mvn test -Dtest="TestGraceHashJoin" -DfailIfNoTests=false

# Full Part 1 suite
mvn test -Dtest="TestBNLJOperator,TestSortOperator,TestSortMergeOperator,TestGraceHashJoin" -DfailIfNoTests=false
```

Recommended implementation order: `BNLJ` → `sortRun` → `mergeSortedRuns` → `mergePass` → `sort` → `SortMergeIterator` → `partition` → `buildAndProbe` → `run` → `getBreakSHJInputs` / `getBreakGHJInputs`.

### Debugging Guides

- [Task 1 Debugging](original-docs/task-1-debugging.md) — visual error output guide for PNLJ and BNLJ test failures; covers the four iterator cases and common mistakes with `markNext()` / `reset()`
- [Task 2 Common Errors](original-docs/task-2-common-errors.md) — GHJ pitfalls: negative hash codes, infinite recursion from a fixed hash seed, and missing output from recursive calls
