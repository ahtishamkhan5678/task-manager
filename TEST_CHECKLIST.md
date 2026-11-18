# Test Checklist

This is the checklist behind `test/TestRunner.java`. Each line is one PASS/FAIL check. Everything runs offline with `FakeLlmClient`, `FaultyLlmClient` and the rule-based strategies, so no API key and no internet are needed.

Run it with `./test.sh`. Last result: **83 passed, 0 failed, 83 total**.

## 1. CRUD

- [x] createTask valid -> 201, id 1, default priority MEDIUM, completed false
- [x] getTask existing -> 200 with the task
- [x] getTask unknown id -> 404 NOT_FOUND
- [x] updateTask -> 200, fields changed, id and createdAt kept
- [x] updateTask unknown id -> 404
- [x] updateTask invalid input -> 400 and task unchanged
- [x] deleteTask -> 200, then getTask -> 404
- [x] deleteTask unknown id -> 404

## 2. Validation and error shape

- [x] empty title -> 400 VALIDATION_ERROR on field title
- [x] blank description (spaces only) -> 400 on field description
- [x] five bad fields -> one 400 with five details
- [x] completed: 'yes', 'TRUE', '' rejected; 'true' accepted
- [x] dueDate 2026-02-30 -> 400 on field dueDate
- [x] leap years: 2028/2000 Feb 29 valid, 2025/1900 Feb 29 invalid, Apr 31 invalid
- [x] bad date formats rejected (2026-1-5, 15/11/2026, empty, month 00, year 0000...)
- [x] priority: 'URGENT' and 'low' rejected, 'HIGH' accepted
- [x] createTask(null) -> 400 not a crash
- [x] error body is exactly {error:{code:String, message:String, details:[{field,issue}]}}
- [x] unexpected exception -> 500 INTERNAL_ERROR in the same error shape

## 3. AI task creation (LlmTaskParser with fakes)

- [x] FakeLlmClient valid JSON -> 201 with title, dueDate, recurrence MONTHLY
- [x] prompt sent to the model contains today's date and the user's text
- [x] reply wrapped in ```json fences is still read correctly
- [x] bad model output (empty title) -> 502 and nothing saved
- [x] bad model output (impossible date) -> 502 and nothing saved
- [x] bad model output (wrong type (title is a number)) -> 502 and nothing saved
- [x] bad model output (malformed text (not JSON)) -> 502 and nothing saved
- [x] bad model output (empty reply) -> 502 and nothing saved
- [x] bad model output (client throws) -> 502 and nothing saved
- [x] bad model output (client times out) -> 502 and nothing saved
- [x] model returns unknown recurrence -> 502 and nothing saved
- [x] Mode.LLM with no LLM_API_KEY -> 503 LLM_UNAVAILABLE (no crash, nothing saved)
- [x] breakDownTask in Mode.LLM with no LLM_API_KEY -> 503 and no subtasks saved
- [x] blank text -> 400 and the model is never called
- [x] RuleBasedTaskParser: 'pay rent on the 1st of every month' -> 'Pay rent', 2026-12-01, MONTHLY
- [x] RuleBasedTaskParser: 'tomorrow' -> 2026-11-16
- [x] ParserFactory AUTO: rule-based without key, LLM parser with key (no network call made)

## 4. Subtasks and breakdown

- [x] createSubtask -> 201 with parentId set
- [x] createSubtask unknown parent -> 404
- [x] subtask of a subtask -> 400 and not saved
- [x] listSubtasks returns only that parent's children
- [x] listSubtasks unknown parent -> 404
- [x] LlmBreakdownStrategy (fake) -> 201, 3 subtasks inheriting dueDate and priority
- [x] breakdown with a malformed line -> 502 and NO subtasks saved
- [x] breakdown where one item has an empty title -> 502, details point at subtasks[1].title, none saved
- [x] breakdown when model call throws -> 502 LLM_CALL_FAILED
- [x] breakDownTask on a subtask -> 400 and the model is never called
- [x] RuleBasedBreakdownStrategy splits on commas and 'and' -> 3 subtasks
- [x] deleteTask on parent removes parent + its 2 subtasks, keeps others

## 5. Idempotency

- [x] same key + same request twice -> same response, ONE task in repository
- [x] same key + different request -> 422 IDEMPOTENCY_KEY_REUSED, still one task
- [x] createTaskFromText x3 with same key -> fake LLM called once, one task
- [x] breakDownTask x2 with same key -> fake LLM called once, still 3 subtasks
- [x] failed (400) response is not stored -> fixing input and retrying with same key gives 201
- [x] null idempotency key -> no deduplication, two tasks
- [x] blank idempotency key -> 400 on field idempotencyKey
- [x] key used by createTask then reused for breakDownTask -> 422
- [x] 20 threads retry with the same key at once -> exactly one task

## 6. Filtering, search, sorting, pagination

- [x] filter completed=true -> 2 tasks
- [x] filter priority=HIGH -> tasks 1 and 4
- [x] search 'RENT' matches title or description (1,3); 'shop' matches 'SHOP' (2)
- [x] sort dueDate desc -> 5,1,4,2 then task without date (3) last
- [x] sort createdAt desc -> newest first 5,4,3,2,1
- [x] pageSize 2: page 1 -> [1,2], page 3 -> [5], totalPages 3
- [x] default page size is 10 and page 1 is the first page
- [x] page beyond the last page -> 200 with empty items and correct totals
- [x] page 0, pageSize 0/1000, unknown sortBy/order/priority -> 400
- [x] completed=false + priority=HIGH + search 'rent' + dueDate asc + page 1 size 1 -> [6], total 2
- [x] listByPriority MEDIUM -> 200 with 2; 'SUPER' -> 400
- [x] plug-in HasDueDateFilter + TitleSort -> Mango(3), Zebra(1)

## 7. Observer events

- [x] audit counts: 1 created, 1 updated, 1 completed, 1 deleted (failed create fires nothing)
- [x] after removeListener, the listener receives no more events
- [x] a listener that throws does not fail createTask, and later listeners still run

## 8. Gaps found in the critique (round 2)

- [x] completeTask -> 200 completed; calling it again changes nothing and fires no 2nd event; unknown id -> 404
- [x] deleting ONE subtask removes only it; parent and sibling stay
- [x] updateTask on a subtask keeps its parentId
- [x] listTasks(null) -> 200 with defaults (page 1, size 10, all 5 tasks)
- [x] search of only spaces is ignored (returns all 5), not 'match nothing'
- [x] SimpleJson reads escapes, \u unicode, numbers, booleans, null, nested objects, and writes them back
- [x] SimpleJson throws IllegalArgumentException for 8 kinds of malformed JSON
- [x] RuleBasedTaskParser: 'next Friday' -> 2026-11-20; '31st' skips 30-day November -> 2026-12-31
- [x] breakdown strips '- '/'* ' bullets; a line with two '|' -> 502 and nothing saved
- [x] breakdown with 11 lines (limit 10) -> 502 and nothing saved
- [x] createTaskFromText: same key + different text -> 422 and the model is not called again

## How this checklist was improved

- **Round 1** compared the AI-generated happy-path suite with the brief's list of edge cases: invalid input, unknown ids, repeated and conflicting idempotency keys, impossible dates, bad parser output, a subtask of a subtask, and pages past the end.
- **Round 2** came from a debugger session ([DEBUGGING.md](DEBUGGING.md)) and a second read of the tests. It added group 8.
- The README (section 7) explains each finding and lists the gaps that are still open.
