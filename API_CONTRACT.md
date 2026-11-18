# API Contract

This file explains how to use the Task Manager API: what each method takes in, what it gives back, and what errors it can return.

There is no web server in this project. Each "endpoint" is just a public method in the `TaskApi` class. Every method returns an `ApiResponse`, which has two parts:

- a **status code** (like 200, 201, 400 or 404)
- a **body** (the data, in JSON style)

The methods never throw an exception to the caller. If something goes wrong, you get an error response instead.

If this project is later moved to Spring Boot, each method can become a real URL. I have written a suggested URL for each one below.

---

## 1. The data

### Task (what you get back)

| Field | Type | What it means |
|---|---|---|
| `id` | number | Given by the system. The first task is 1, then 2, 3 and so on. |
| `title` | text | Short name of the task. Never empty. |
| `description` | text | More detail about the task. Never empty. |
| `completed` | true / false | Is the task done? Starts as `false`. |
| `dueDate` | text or null | Date in `YYYY-MM-DD` format, for example `2026-12-01`. Always a real date. |
| `priority` | text | `LOW`, `MEDIUM` or `HIGH`. If you don't give one, it is `MEDIUM`. |
| `parentId` | number or null | Only for subtasks. It is the id of the main task. |
| `recurrence` | text or null | How often it repeats: `DAILY`, `WEEKLY`, `MONTHLY` or `YEARLY`. |
| `createdAt` | number | The time the task was made, in milliseconds. |

### TaskRequest (what you send)

Everything you send is plain text (a `String`). The system checks it and decides if it is OK.

| Field | Must I send it? | Rule |
|---|---|---|
| `title` | Yes | Cannot be empty or only spaces. |
| `description` | Yes | Cannot be empty or only spaces. |
| `completed` | No (empty means `false`) | Must be exactly `"true"` or `"false"`. `"yes"` or `"TRUE"` will fail. |
| `dueDate` | No | Must be a real date like `2026-12-01`. `2026-02-30` fails because February has no 30th. `2025-02-29` fails because 2025 is not a leap year. |
| `priority` | No (empty means `MEDIUM`) | Must be exactly `"LOW"`, `"MEDIUM"` or `"HIGH"`, in capital letters. |
| `recurrence` | No | Must be exactly `"DAILY"`, `"WEEKLY"`, `"MONTHLY"` or `"YEARLY"`. |

### TaskQuery (for searching and listing)

All fields are optional. You build it like this: `TaskQuery.builder().priority("HIGH").page(1).build()`

| Field | If you skip it | What it does |
|---|---|---|
| `completed` | shows all | `true` shows only finished tasks. `false` shows only open tasks. |
| `priority` | shows all | Shows only tasks with this priority. |
| `search` | shows all | Finds tasks where the title or description contains this text. Capital letters don't matter. |
| `sortBy` | `createdAt` | Sort by `createdAt` (when it was made) or `dueDate`. |
| `order` | `asc` | `asc` means oldest or earliest first. `desc` means newest or latest first. |
| `page` | `1` | Which page you want. Page 1 is the first page. |
| `pageSize` | `10` | How many tasks per page, from 1 to 100. |
| `filter(...)` | none | Lets you add your own extra filter class. |

When sorting by `dueDate`, tasks with no due date always come at the end.

### Page (what a list gives back)

```json
{ "items": [ ...tasks... ], "page": 1, "pageSize": 10, "totalItems": 13, "totalPages": 2 }
```

---

## 2. Errors

Every error looks the same, so the person using the API only has to handle one shape:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Validation failed",
    "details": [ { "field": "title", "issue": "must not be empty" } ]
  }
}
```

- `code` is always text. A program can check it.
- `message` is always text. A person can read it.
- `details` is always a list. It says which field is wrong and why. It can be empty.

If you send many wrong fields at once, you get **all** of the problems in one response, not just the first one.

### All error codes

| Status | Code | When it happens |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Something you sent is wrong. For example: empty title, bad date, wrong priority, bad page number, empty text for the AI, a blank idempotency key, or trying to add a subtask under a subtask. |
| 404 | `NOT_FOUND` | No task has that id. |
| 422 | `IDEMPOTENCY_KEY_REUSED` | You used the same idempotency key before, but with a different request. |
| 500 | `INTERNAL_ERROR` | Something unexpected broke inside the program. No inside details are shown. |
| 502 | `LLM_CALL_FAILED` | The AI could not be reached, gave an error, or took too long. If the AI says it is busy (429, 500, 502 or 503), the system tries again up to 3 times first. |
| 502 | `LLM_INVALID_OUTPUT` | The AI answered, but the answer was not usable: not JSON, an empty title, an impossible date, a wrong type, or a badly formatted subtask line. **Nothing is saved.** |
| 503 | `LLM_UNAVAILABLE` | The AI feature is turned on, but the `LLM_API_KEY` is not set. |

How it works inside: each error is a custom exception. They all come from one parent class, `ApiException`, which holds the status and the code. `TaskApi` catches them, and `ErrorResponseFactory` turns them into the JSON above. It is the only place where the error shape is built.

---

## 3. Safe retries (idempotency)

Sometimes a network call fails and the user clicks "try again". We don't want two copies of the same task. So three methods accept an **idempotency key**. This is any unique text the caller picks, like `"order-123"`:

- `createTask`
- `createTaskFromText`
- `breakDownTask`

The key is optional. You can pass `null`.

| What happens | Result |
|---|---|
| No key (`null`) | Works normally. No duplicate check. |
| Empty key (`""` or spaces) | 400 error. |
| First time this key is used | The request runs. If it works, the answer is saved with the key. |
| Same key, same request again | You get the **saved answer** back. Nothing new is created, and the AI is **not** called again. |
| Same key, but a different request | 422 error. |
| The first try with this key failed | Failures are not saved. You can fix your input and try again with the same key. |

To know if a request is "the same", the system joins all the request values into one string and compares them. For example, `createTask` uses the method name plus the title, description, completed, due date, priority and recurrence.

---

## 4. The methods

### createTask(request, idempotencyKey)
URL idea: `POST /tasks`
- **201**: the new task
- Errors: **400** (bad input), **422** (key reused)
- Sends the event `TASK_CREATED`

### getTask(id)
URL idea: `GET /tasks/{id}`
- **200**: the task
- Errors: **404** (no such task)

### listTasks(query)
URL idea: `GET /tasks?priority=HIGH&page=1`
- **200**: one page of tasks. If you ask for a page after the last one, you get an empty list. This is not an error.
- Errors: **400** (bad priority, sort, order, page or page size)
- If the query is `null`, all the defaults are used.

### updateTask(id, request)
URL idea: `PUT /tasks/{id}`
- **200**: the updated task. You send all the fields again, and the same rules as create apply. The id, parent and created time do not change.
- Errors: **400** (bad input), **404** (no such task)
- Sends `TASK_COMPLETED` if the task just became done, otherwise `TASK_UPDATED`

### completeTask(id)
URL idea: `POST /tasks/{id}/complete`
- **200**: the task, now marked as done. Calling it again on a done task changes nothing.
- Errors: **404**
- Sends `TASK_COMPLETED` (only the first time)

### deleteTask(id)
URL idea: `DELETE /tasks/{id}`
- **200**: `{ "deletedId": 8, "deletedIds": [9, 10, 8], "deletedCount": 3 }`
- Deleting a main task **also deletes its subtasks**.
- Errors: **404**
- Sends `TASK_DELETED` for every task removed

### createSubtask(parentId, request)
URL idea: `POST /tasks/{parentId}/subtasks`
- **201**: the new subtask, with `parentId` filled in
- Errors: **400** (bad input, or the parent is already a subtask, because only one level is allowed), **404** (parent not found)
- Sends `TASK_CREATED`

### listSubtasks(parentId)
URL idea: `GET /tasks/{parentId}/subtasks`
- **200**: `{ "parentId": 8, "count": 2, "items": [ ...subtasks... ] }`
- Errors: **404**

### createTaskFromText(text, idempotencyKey)
URL idea: `POST /tasks/from-text`
- You write a normal sentence, like *"Remind me to pay rent on the 1st of every month"*. The AI turns it into a task.
- **201**: the new task. The title, description, due date and repeat come from the AI. Done is `false` and priority is `MEDIUM`.
- We never trust the AI blindly. Its answer goes through the same checks as a normal task.
- Errors:
  - **400**: the text is empty. The AI is not called.
  - **422**: the key was reused.
  - **502**: the AI failed or gave a bad answer. Nothing is saved.
  - **503**: no API key.
- Sends `TASK_CREATED`

### breakDownTask(id, idempotencyKey)
URL idea: `POST /tasks/{id}/breakdown`
- The AI splits a big task into smaller subtasks.
- **201**: `{ "parentId": 3, "count": 3, "subtasks": [ ... ] }`. Each subtask gets the parent's due date and priority.
- All subtasks are checked first. If even **one** is bad, **none** are saved.
- Errors:
  - **400**: the task is already a subtask. The AI is not called.
  - **404**: no such task.
  - **422**: the key was reused.
  - **502**: the AI failed or gave a bad answer. Nothing is saved.
  - **503**: no API key.
- Sends `TASK_CREATED` for each new subtask

### listByPriority(level)
URL idea: `GET /tasks/priority/{level}`
- **200**: `{ "priority": "HIGH", "count": 1, "items": [ ... ] }`
- Errors: **400** if the level is not exactly `LOW`, `MEDIUM` or `HIGH`

---

## 5. Events

Other parts of the program can "listen" to what happens to tasks (the Observer pattern). A listener gets told **after** a change works. If an operation fails, no event is sent.

| Event | When it is sent |
|---|---|
| `TASK_CREATED` | A task or subtask is made (including by the AI) |
| `TASK_UPDATED` | A task is changed |
| `TASK_COMPLETED` | A task becomes done |
| `TASK_DELETED` | A task is deleted (one event for each subtask too) |

If a listener has a bug and crashes, the error is printed, but the operation still works and the other listeners still get the event.
