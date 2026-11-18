# Debugging Session: Parser and Breakdown Output (Step 9)

Step 9 asks to "use IDE breakpoints to inspect the parser and breakdown output". This file records that session: where the breakpoints were, what the variables held, and what it taught us.

**Tool used:** `jdb`, the debugger that ships with the JDK. IntelliJ's debugger sets the same kind of breakpoints through the same JVM debugging interface. Section 4 shows how to repeat this session in IntelliJ.

**Program debugged:** `test.TestRunner`, offline, with `FakeLlmClient` / `FaultyLlmClient`.

## 1. Breakpoints

| # | Location | Why there |
|---|---|---|
| B1 | `parser/LlmTaskParser.java:71`: `return new ParsedTask(title, description, dueDate, recurrence);` | Shows the raw model `reply` **and** the fields the parser extracted from it, before any validation |
| B2 | `service/TaskService.java:193`: `throw BadGatewayException.invalidOutput(...)` | Fires only when the model's task failed validation, which proves "never trust the model" |
| B3 | `parser/LlmBreakdownStrategy.java:73`: `requests.add(...)` inside the line loop | Shows each breakdown `line`, the `separator` index, and the `title` / `description` split out of it |

Commands used:

```
javac -g --release 17 -d out $(find src -name "*.java")
jdb -classpath out test.TestRunner
> stop at parser.LlmTaskParser:71
> stop at service.TaskService:193
> stop at parser.LlmBreakdownStrategy:73
> run
> locals        (at every stop)
> cont
```

## 2. What the variables held (copied from the session)

**B1, valid fake reply** (test: *FakeLlmClient valid JSON → 201*)
```
text        = "Remind me to pay rent on the 1st of every month"
reply       = "{"title":"Pay rent","description":"Pay the monthly rent","dueDate":"2026-12-01","recurrence":"MONTHLY"}"
title       = "Pay rent"
dueDate     = "2026-12-01"
recurrence  = "MONTHLY"
```

**B1, reply wrapped in a code fence** (test: *```json fences accepted*)
```
reply = "```json\n{...}\n```"   ->   title = "Pay rent", dueDate = "2026-12-01"
```
The fences were stripped before `SimpleJson` read the reply.

**B1 then B2, empty title** (`FaultyLlmClient(EMPTY_TITLE)`)
```
B1: reply = "{"title": "", "description": "Pay the rent", ...}"   ->   title = ""
B2: e = ValidationException   (TaskValidator rejected title "")   ->   502, nothing saved
```

**B1 then B2, impossible date** (`FaultyLlmClient(IMPOSSIBLE_DATE)`)
```
B1: dueDate = "2026-02-30"
B2: e = ValidationException   (dueDate is not a real date)        ->   502, nothing saved
```

**B1 then B2, unknown recurrence**
```
B1: reply = "{"title":"Gym",...,"recurrence":"every tuesday"}"   ->   recurrence = "every tuesday"
B2: e = ValidationException   (recurrence not DAILY/WEEKLY/MONTHLY/YEARLY) -> 502
```

**B3, valid breakdown** (`FakeLlmClient` with 3 lines)
```
line = "Book venue|Call three venues and compare prices"   separator = 10  title = "Book venue"        description = "Call three venues and compare prices"
line = "Send invitations|Email all guests"                 separator = 16  title = "Send invitations"  description = "Email all guests"
line = "Order cake|Order a chocolate cake"                 separator = 10  title = "Order cake"        description = "Order a chocolate cake"
```

**B3, empty-title line**
```
line = "|Missing title"   separator = 0   title = ""   description = "Missing title"
```
The parser accepted this line, and the service validation rejected it afterwards (502, field `subtasks[1].title`).

B1 was **not** hit for the `WRONG_TYPE`, `MALFORMED_TEXT`, `EMPTY_REPLY`, `THROWS` and `TIMEOUT` faults. Those are rejected earlier, inside the parser (wrong type, not JSON, empty reply) or before it (the client threw).

## 3. What the session taught us (used in the test critique)

1. **There are two lines of defence, and both work.** The parser only checks *format* (is it JSON, are fields strings, is there a `|`). Content rules such as an empty title, an impossible date or an unknown recurrence are left to `TaskValidator`. B1 showed `title = ""`, `dueDate = "2026-02-30"` and `recurrence = "every tuesday"` getting past the parser. B2 showed each one stopped before saving. This is the intended "never trust the model" design.
2. **One test proved less than its name suggested.** In the `BAD_BREAKDOWN_LINE` test, B3 showed that line 1 was fine and line 3 (`|Missing title`) was parsed, but the whole reply had already failed because of line 2 (no `|`). So that test only proves the *missing separator* case. The *empty title* case is proven by a separate test, which already existed. The debugger confirmed why both are needed.
3. **The fakes ignore the prompt.** For the input "pay rent next Friday", the fake still returned `2026-12-01`, because a `FakeLlmClient` always gives the same reply. So the offline tests prove the **plumbing** (prompt contains today's date, parsing, validation, saving), not whether the model resolves dates well. Only the live run against Gemini (README section 2, "Verified with a real model") checks the prompt's quality.
4. **The debugger raised new questions, which led to the round-2 tests** in the README critique. Examples: what happens with bullets, with two `|` on one line, or with more than 10 lines? Is the JSON reader itself correct?

## 4. Repeating this in IntelliJ

1. Mark `src` as **Sources Root** (right-click `src` → *Mark Directory as* → *Sources Root*).
2. Click in the gutter to add breakpoints at B1, B2 and B3 from the table above.
3. Right-click `src/test/TestRunner.java` → **Debug 'TestRunner.main()'**.
4. At each stop, look at the **Variables** panel for `reply`, `title`, `dueDate`, `line` and `separator`, then press **Resume (F9)**.
5. Optional: right-click a breakpoint and set a **Condition**, such as `title.isEmpty()` on B1, to stop only on bad model output.
