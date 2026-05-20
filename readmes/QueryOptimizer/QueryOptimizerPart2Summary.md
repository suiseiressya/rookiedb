# Proj3 Part 2: Query Optimization

## Overview

This part implements a **cost-based query optimizer** based on the System R Selinger algorithm. Given a multi-table query, the optimizer searches the space of left-deep join trees and returns the plan with the lowest estimated I/O cost — rather than blindly joining tables in the order the user wrote them.

The optimizer is structured as three cooperating methods in `QueryPlan.java`:

| Method | Pass | Responsibility |
|--------|------|---------------|
| `minCostSingleAccess` | Pass 1 | Find the cheapest way to scan each individual table (seq scan vs. index scan + pushed-down selects) |
| `minCostJoins` | Pass i > 1 | Extend the previous pass's best plans by joining one new table at a time; return best plans for all sets of i tables |
| `execute` | Driver | Orchestrate the DP loop, pick the global winner, then attach GroupBy / Project / Sort / Limit on top |

---

## Caveats & Limitations

| Omitted Feature | What a Real DB Would Do |
|-----------------|------------------------|
| **No interesting orders** | System R tracks sort orders that avoid later sort operators; this optimizer ignores them entirely |
| **Cartesian products disallowed** | A set of tables is only considered if a join predicate connects it — purely for correctness, not for performance |
| **Only SNLJ and BNLJ considered** | `minCostJoinType` builds both and picks the cheaper one; GHJ/SMJ have `estimateIOCost` values that steer the optimizer away |
| **No post-join predicate pushdown** | Predicates that span ≥ 2 tables and can't be expressed as a join column equality (e.g. `t1.a = t2.b OR t2.b = t2.c`) are unsupported — the schema enforces single-column predicates in WHERE and two-column predicates only in ON |
| **Single index per table** | For each table, at most one index is chosen as its base scan; any other eligible predicates on that table become `SelectOperator` layers on top. Multiple tables can each use their own independent indexes |

---

## Cost Model

All cost decisions use a single signal: `estimateIOCost()`, which returns an integer
representing estimated disk I/O operations. Cardinality statistics come from
`estimateStats()`, which provides page and record counts without any runtime feedback.

- **Sequential scan:** cost = number of pages in the table
- **Index scan:** cost = estimated pages accessed via the B+ tree (depends on selectivity)
- **SNLJ:** `numLeftRecords × numRightPages + leftIOCost`
- **BNLJ:** `ceil(numLeftPages / (B-2)) × numRightIOCost + leftIOCost`
- The optimizer picks among plans purely on these static estimates — no adaptive re-planning

---

## Project Files

| File | Role |
|------|------|
| `query/QueryPlan.java` | The entire optimizer lives here: `minCostSingleAccess`, `minCostJoins`, `execute`, and all helper methods |
| `query/SequentialScanOperator.java` | Full table scan; always possible and the default baseline |
| `query/IndexScanOperator.java` | Index-based scan; used when an index exists on a predicate column and the operator is not `!=` |
| `query/SelectOperator.java` | Wraps a source and filters records; stacked on top of scans during predicate pushdown |

---

## Algorithm Details

### Pass 1 — `minCostSingleAccess`

Determines the cheapest way to read a single table, then pushes down all applicable WHERE predicates.

**Steps:**
1. Start with a `SequentialScanOperator` as the baseline; record its `estimateIOCost()`.
2. Call `getEligibleIndexColumns(table)` to find all select predicates on this table for which an index exists and the operator is not `!=`.
3. For each eligible index, create an `IndexScanOperator` and compare its cost. Track the overall minimum and the predicate index that won.
4. Call `addEligibleSelections(bestOperator, bestExceptIndex)` to wrap the base scan in `SelectOperator` layers for every remaining WHERE predicate on this table. The `except` argument skips the predicate already consumed by the index scan (pass `-1` if the best operator is a sequential scan).

**Output shape:**

```
SelectOperator(pred_n)           ← pushed-down predicates (zero or more)
    └── SelectOperator(pred_1)
            └── IndexScanOperator  (or SequentialScanOperator)
```

**Why push down selects here?**  
Joins are expensive. The fewer rows a join sees, the cheaper it is. Filtering before the join rather than after is the classic "predicate pushdown" optimization:

```
// Bad: filter after join
SelectOperator(age > 25)
    └── JoinOperator(Sailors ⋈ Reserves)

// Good: filter before join
JoinOperator
    └── SelectOperator(age > 25)
            └── SequentialScanOperator(Sailors)
```

---

### Pass i > 1 — `minCostJoins`

Extends the previous pass's best i-1–table plans by joining each one with one new table.

**Inputs:**
- `prevMap` — maps every set of i-1 tables to the cheapest operator over those tables (output of the previous pass).
- `pass1Map` — maps every singleton `{table}` to its cheapest single-table access (from Pass 1). Used to fetch a fresh scan of the new table being added.

**Core loop:**

```
result = {}   ← fresh map, starts empty for this pass
for each (tableSet → leftOp) in prevMap:
    for each joinPredicate (leftTable.leftCol = rightTable.rightCol):
        if tableSet contains leftTable but not rightTable:
            rightOp = pass1Map[{rightTable}]
            candidate = minCostJoinType(leftOp, rightOp, leftCol, rightCol)
            newSet = tableSet ∪ {rightTable}
        else if tableSet contains rightTable but not leftTable:
            leftOp2 = pass1Map[{leftTable}]
            candidate = minCostJoinType(leftOp, leftOp2, rightCol, leftCol)
            newSet = tableSet ∪ {leftTable}
        else:
            skip (cartesian product or already fully joined)

        if newSet not in result OR candidate.cost < result[newSet].cost:
            result[newSet] = candidate
```

**Why use `pass1Map` for the new table instead of the previous pass?**  
The optimizer builds left-deep trees: the left subtree grows with each pass, and the right side is always a fresh single-table access. Using `pass1Map` for the right operand enforces this shape and prevents Cartesian products (the join predicate guarantees a connection).

---

### `minCostJoinType`

Given two operators and join columns, picks the cheaper of SNLJ and BNLJ:

```java
allJoins = [SNLJOperator(...), BNLJOperator(...)]
return argmin(join.estimateIOCost() for join in allJoins)
```

BNLJ is always at least as good as SNLJ. It loads B-2 left pages at once and scans the right once per chunk, whereas SNLJ scans the right once per left *record*. The only degenerate case is when every left page contains exactly one record — then `ceil(numLeftPages / (B-2)) == numLeftRecords` and the two algorithms produce identical cost. The relative sizes of left vs. right have no bearing on which algorithm wins.

---

### `execute`

Orchestrates the full DP search and attaches the remaining operators:

```
Pass 1:
    pass1Map = { {t} → minCostSingleAccess(t) for t in tableNames }

Pass i:
    prevMap = pass1Map
    while prevMap.size() > 1:
        prevMap = minCostJoins(prevMap, pass1Map)

finalOperator = prevMap's single remaining operator
addGroupBy() → addProject() → addSort() → addLimit()
return finalOperator.iterator()
```

The loop terminates when `prevMap` has exactly one entry, meaning all tables have been joined. `minCostOperator` (used implicitly via the single remaining entry) then selects the winner.

**Why does `prevMap.size() == 1` mean we're done?**  
Each pass merges one new table into every existing group. When there is only one group left, it must contain all tables — there is nothing left to join.

**DP state evolution — 3-table example:**

```
Tables: A, B, C     Join predicates: A-B, B-C     (no A-C predicate exists)

pass1Map = { {A}→opA, {B}→opB, {C}→opC }

Pass 2: prevMap = minCostJoins(pass1Map, pass1Map)
  → { {A,B} → bestJoin(opA, opB),   ← via A-B predicate, right side = pass1Map[{B}]
      {B,C} → bestJoin(opB, opC) }  ← via B-C predicate
     Note: {A,C} is never produced — no A-C predicate exists, so joining A and C
     would be a cartesian product. The algorithm simply skips it.

Pass 3: prevMap = minCostJoins({{A,B},{B,C}} map, pass1Map)
  → { {A,B,C} → cheaper of:
        bestJoin({A,B}op, opC)  ← left={A,B} grows, right=C via B-C predicate
        bestJoin({B,C}op, opA)  ← left={B,C} grows, right=A via A-B predicate
      (both candidates computed; result keeps the lower-cost one) }

Final: prevMap has exactly 1 entry → set as finalOperator
```

The right-hand operand is always drawn from `pass1Map` (a single table), enforcing
left-deep tree shape: the left subtree grows every pass, the right side is always a leaf.

---

## Key Design Decisions

| Decision | Detail |
|----------|--------|
| Sequential scan is always the baseline | `minCostSingleAccess` always builds a `SequentialScanOperator` first; an index scan is only chosen if it beats this baseline. Ensures correctness even when no index exists |
| `except` index in `addEligibleSelections` | When an index scan is chosen, the predicate it used must not be applied again as a `SelectOperator`. Passing `bestExceptIndex` skips exactly that predicate |
| `pass1Map` frozen across DP passes | The right-hand side of every join is always fetched from `pass1Map`, not `prevMap`. This enforces left-deep tree shape and makes Cartesian-product detection straightforward |
| Cartesian products skipped, not penalized | If a join predicate's two tables are both already in the current set (or neither is), the predicate is simply skipped. No cost is computed; the candidate is never added to `result` |
| `minCostJoins` does not push down selects | All single-table predicates were already pushed in Pass 1. The only remaining unprocessed predicates in pass i > 1 are join conditions, which are handled structurally by the join operator itself |
| `prevMap` starts as a copy of `pass1Map` | The DP loop uses `prevMap` as both input and output variable. Initializing it to `pass1Map` lets the first iteration of `minCostJoins` treat single-table plans as the "previous pass" |

---

## Before vs. After

**Naive optimizer** (using executeNaive()):

```sql
EXPLAIN SELECT * FROM Students AS s INNER JOIN Enrollments AS e ON s.sid = e.sid;
Project (cost=603)
	columns: (s.sid, s.name, s.major, s.gpa, e.sid, e.cid)
	-> SNLJ on s.sid=e.sid (cost=603)
		-> Seq Scan on s (cost=3)
		-> Seq Scan on e (cost=3)

XPLAIN SELECT c.name, s.major, COUNT(*) FROM Students AS s INNER JOIN Enrollments AS e ON s.sid = e.sid INNER JOIN Courses AS c ON e.cid = c.cid WHERE c.name = 'CS 186' GROUP BY s.major, c.name;
Project (cost=1598)
	columns: (c.name, s.major, COUNT(*))
	-> Group By (cost=1598)
	  columns: (s.major, c.name)
		-> Select c.name=CS 186 (cost=1598)
			-> SNLJ on e.cid=c.cid (cost=1598)
				-> SNLJ on s.sid=e.sid (cost=603)
    -> Seq Scan on s (cost=3)
    -> Seq Scan on e (cost=3)
    -> Seq Scan on c (cost=1)
```

**Optimized**:
```sql
EXPLAIN SELECT * FROM Students AS s INNER JOIN Enrollments AS e ON s.sid = e.sid;
Project (cost=6)
	columns: (s.sid, s.name, s.major, s.gpa, e.sid, e.cid)
	-> BNLJ on e.sid=s.sid (cost=6)
		-> Seq Scan on e (cost=3)
		-> Seq Scan on s (cost=3)
        
EXPLAIN SELECT c.name, s.major, COUNT(*) FROM Students AS s INNER JOIN Enrollments AS e ON s.sid = e.sid INNER JOIN Courses AS c ON e.cid = c.cid WHERE c.name = 'CS 186' GROUP BY s.major, c.name;
Project (cost=11)
	columns: (c.name, s.major, COUNT(*))
	-> Group By (cost=11)
	  columns: (s.major, c.name)
		-> BNLJ on e.cid=c.cid (cost=7)
			-> BNLJ on e.sid=s.sid (cost=6)
				-> Seq Scan on e (cost=3)
				-> Seq Scan on s (cost=3)
			-> Materialize (cost: 1)
				-> Select c.name=CS 186 (cost=1)
					-> Seq Scan on c (cost=1)
```
---

## Tests

```bash
# Pass 1: single-table access selection
mvn test -Dtest="TestSingleAccess" -DfailIfNoTests=false

# Pass i: join selection
mvn test -Dtest="TestOptimizationJoins" -DfailIfNoTests=false

# Full Part 2 suite
mvn test -Dtest="TestSingleAccess,TestOptimizationJoins,TestBasicQuery" -DfailIfNoTests=false
```

Recommended implementation order: `minCostSingleAccess` → `minCostJoins` → `execute`.
