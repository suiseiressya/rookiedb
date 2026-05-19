# Task 2 Common Errors

## Index out of bounds error while partitioning

Hash codes can be negative. Make sure you handle that case. The hash codes can also be larger than the number of partitions, so make sure you handle that too. We recommend you look at [SHJOperator'](https://github.com/berkeley-cs186/sp26-rookiedb/blob/master/src/main/java/edu/berkeley/cs186/database/query/join/SHJOperator.java#L73-L76)s implementation to make sure you partition correctly with hash codes.

## Reached the max number of passes cap

This means that you're doing recursive partitioning infinitely. The most likely cause of this is partitioning using the the same hash function every single time. Make sure to update your hash func calls so that the hash function is updated each time.

If you're certain that you're doing both of those things, make sure your condition for recursive partitioning is correct. An off by one (for example `<=` vs `<` ) is enough to make it so you never reach the build and probe phase.

## Code running forever/recursion depth limit exceeded/java.lang.OutOfMemoryError

Make sure every time you make a recursive call to run that you increment the pass number.

## AssertionError: Expected: 1674 Actual: 91

Make sure when you recursively call run that you add all of the resulting records to your output. Additionally make sure that whenever you call buildAndProbe that you also add those records to your output.


---

# Agent Instructions: Querying This Documentation

If you need additional information that is not directly available in this page, you can query the documentation dynamically by asking a question.

Perform an HTTP GET request on the current page URL with the `ask` query parameter:

```
GET https://cs186.gitbook.io/project/assignments/proj3/part-1-join-algorithms/task-2-common-errors.md?ask=<question>
```

The question should be specific, self-contained, and written in natural language.
The response will contain a direct answer to the question and relevant excerpts and sources from the documentation.

Use this mechanism when the answer is not explicitly present in the current page, you need clarification or additional context, or you want to retrieve related documentation sections.
