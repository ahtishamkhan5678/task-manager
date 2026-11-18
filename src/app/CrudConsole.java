package app;

import api.ApiResponse;
import api.TaskApi;
import model.TaskRequest;
import parser.ParserFactory;
import query.TaskQuery;
import repository.InMemoryTaskRepository;
import repository.TaskRepository;
import service.TaskService;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Interactive, menu-driven console for trying every TaskApi operation by hand.
 * Run it from IntelliJ (run configuration "CrudConsole") or with:
 *   javac -d out $(find src -name "*.java") && java -cp out app.CrudConsole
 *
 * Every answer is sent to the API as raw text (like TaskRequest), so you can also type
 * invalid values on purpose and see the validation errors.
 * Data lives in memory only: restarting the console starts with an empty task list.
 */
public class CrudConsole {

    private static final Scanner IN = new Scanner(System.in);
    private static final String LINE = "-".repeat(78);

    private static TaskApi api;
    private static TaskRepository repo;
    private static boolean showJson = false;

    public static void main(String[] args) {
        repo = InMemoryTaskRepository.getInstance();
        boolean hasKey = ParserFactory.hasKey(System.getenv(ParserFactory.API_KEY_ENV));
        ParserFactory.Mode mode = hasKey ? ParserFactory.Mode.LLM : ParserFactory.Mode.RULE_BASED;

        TaskService service = new TaskService(repo,
                ParserFactory.createTaskParser(mode), ParserFactory.createBreakdownStrategy(mode));
        service.addListener((eventType, task) ->
                System.out.println("   (event fired: " + eventType + " for task #" + task.getId() + ")"));
        api = new TaskApi(service);

        printWelcome(hasKey);
        while (true) {
            printMenu();
            String choice = ask("Choose an option");
            if (choice == null) {
                continue;
            }
            System.out.println();
            switch (choice) {
                case "1" -> addTask();
                case "2" -> viewTask();
                case "3" -> viewAllTasks();
                case "4" -> searchTasks();
                case "5" -> editTask();
                case "6" -> markDone();
                case "7" -> deleteTask();
                case "8" -> addSubtask();
                case "9" -> viewSubtasks();
                case "10" -> createFromSentence(hasKey);
                case "11" -> breakDown(hasKey);
                case "12" -> tasksByPriority();
                case "13" -> {
                    showJson = !showJson;
                    System.out.println("Raw JSON responses are now " + (showJson ? "SHOWN" : "HIDDEN") + ".");
                }
                case "14" -> printHelp();
                case "0" -> {
                    System.out.println("Goodbye! (Tasks are kept in memory only, so they are gone now.)");
                    return;
                }
                default -> System.out.println("Please type one of the numbers shown in the menu.");
            }
        }
    }

    // =====================================================================
    // Menu actions
    // =====================================================================

    private static void addTask() {
        title("Add a task");
        TaskRequest request = readTaskFields();
        String key = ask("Idempotency key [optional - only to test safe retries, Enter = skip]");
        ApiResponse response = api.createTask(request, key);
        if (report(response)) {
            Map<String, Object> task = body(response);
            System.out.println("Task #" + task.get("id") + " \"" + task.get("title") + "\" was saved.");
            printCard(task);
        }
    }

    private static void viewTask() {
        title("View one task");
        Integer id = askId("Task id");
        if (id == null) {
            return;
        }
        ApiResponse response = api.getTask(id);
        if (report(response)) {
            printCard(body(response));
        }
    }

    private static void viewAllTasks() {
        title("All tasks");
        ApiResponse response = api.listTasks(TaskQuery.builder().pageSize(100).build());
        if (report(response)) {
            printTable(items(response, "items"));
        }
    }

    private static void searchTasks() {
        title("Search, filter, sort and page");
        System.out.println("Press Enter to skip any filter.");
        TaskQuery.Builder query = TaskQuery.builder();

        String completed = ask("Show only done or open tasks? (true = done, false = open)");
        if (completed != null) {
            query.completed(Boolean.parseBoolean(completed));
        }
        query.priority(ask("Only this priority (LOW / MEDIUM / HIGH)"));
        query.search(ask("Text to search in title or description"));
        String sortBy = ask("Sort by (createdAt / dueDate) [Enter = createdAt]");
        if (sortBy != null) {
            query.sortBy(sortBy);
        }
        String order = ask("Order (asc / desc) [Enter = asc]");
        if (order != null) {
            query.order(order);
        }
        Integer page = askNumber("Page number [Enter = 1]");
        if (page != null) {
            query.page(page);
        }
        Integer size = askNumber("Tasks per page [Enter = 10]");
        if (size != null) {
            query.pageSize(size);
        }

        ApiResponse response = api.listTasks(query.build());
        if (report(response)) {
            Map<String, Object> result = body(response);
            printTable(items(response, "items"));
            System.out.println("Page " + result.get("page") + " of " + result.get("totalPages")
                    + "  |  " + result.get("totalItems") + " matching task(s)  |  " + result.get("pageSize") + " per page");
        }
    }

    private static void editTask() {
        title("Edit a task");
        Integer id = askId("Task id to edit");
        if (id == null) {
            return;
        }
        ApiResponse current = api.getTask(id);
        if (!current.isSuccess()) {
            report(current);
            return;
        }
        Map<String, Object> task = body(current);
        System.out.println("Current values:");
        printCard(task);
        System.out.println("Type a new value, or press Enter to keep the current one. Type - to clear the due date.");

        String title = keep(ask("Title [" + task.get("title") + "]"), text(task, "title"));
        String description = keep(ask("Description [" + task.get("description") + "]"), text(task, "description"));
        String completed = keep(ask("Done? true/false [" + task.get("completed") + "]"), text(task, "completed"));
        String dueAnswer = ask("Due date YYYY-MM-DD [" + orNone(task.get("dueDate")) + "]");
        String dueDate = "-".equals(dueAnswer) ? null : keep(dueAnswer, text(task, "dueDate"));
        String priority = keep(ask("Priority LOW/MEDIUM/HIGH [" + task.get("priority") + "]"), text(task, "priority"));

        ApiResponse response = api.updateTask(id, new TaskRequest(title, description, completed, dueDate, priority,
                text(task, "recurrence")));
        if (report(response)) {
            System.out.println("Task #" + id + " was updated.");
            printCard(body(response));
        }
    }

    private static void markDone() {
        title("Mark a task as done");
        Integer id = askId("Task id");
        if (id == null) {
            return;
        }
        ApiResponse response = api.completeTask(id);
        if (report(response)) {
            System.out.println("Task #" + id + " \"" + body(response).get("title") + "\" is marked as done.");
        }
    }

    private static void deleteTask() {
        title("Delete a task");
        Integer id = askId("Task id to delete");
        if (id == null) {
            return;
        }
        ApiResponse existing = api.getTask(id);
        if (!existing.isSuccess()) {
            report(existing);
            return;
        }
        ApiResponse children = api.listSubtasks(id);
        int subtaskCount = children.isSuccess() ? ((Number) body(children).get("count")).intValue() : 0;
        String warning = subtaskCount > 0 ? " and its " + subtaskCount + " subtask(s)" : "";
        String confirm = ask("Delete task #" + id + " \"" + body(existing).get("title") + "\"" + warning + "? (y/n)");
        if (!"y".equalsIgnoreCase(confirm)) {
            System.out.println("Cancelled. Nothing was deleted.");
            return;
        }
        ApiResponse response = api.deleteTask(id);
        if (report(response)) {
            System.out.println("Deleted " + body(response).get("deletedCount") + " task(s): ids "
                    + body(response).get("deletedIds"));
        }
    }

    private static void addSubtask() {
        title("Add a subtask");
        Integer parentId = askId("Parent task id");
        if (parentId == null) {
            return;
        }
        ApiResponse parent = api.getTask(parentId);
        if (parent.isSuccess()) {
            System.out.println("Adding a subtask under #" + parentId + " \"" + body(parent).get("title") + "\".");
        }
        ApiResponse response = api.createSubtask(parentId, readTaskFields());
        if (report(response)) {
            System.out.println("Subtask #" + body(response).get("id") + " was added under task #" + parentId + ".");
            printCard(body(response));
        }
    }

    private static void viewSubtasks() {
        title("View subtasks");
        Integer parentId = askId("Parent task id");
        if (parentId == null) {
            return;
        }
        ApiResponse response = api.listSubtasks(parentId);
        if (report(response)) {
            printTable(items(response, "items"));
        }
    }

    private static void createFromSentence(boolean hasKey) {
        title("Create a task from a sentence (AI)");
        System.out.println(hasKey
                ? "Using the real language model."
                : "No LLM_API_KEY set: using the offline rule-based parser (understands 'tomorrow', '1st', 'every month'...).");
        String sentence = ask("Describe the task, e.g. Remind me to pay rent on the 1st of every month");
        String key = ask("Idempotency key [optional, Enter = skip]");
        ApiResponse response = api.createTaskFromText(sentence, key);
        if (report(response)) {
            System.out.println("The sentence was turned into task #" + body(response).get("id") + ".");
            printCard(body(response));
        }
    }

    private static void breakDown(boolean hasKey) {
        title("Break a task into subtasks (AI)");
        System.out.println(hasKey
                ? "Using the real language model."
                : "No LLM_API_KEY set: using the offline splitter (splits the description on commas and 'and').");
        Integer id = askId("Task id to break down");
        if (id == null) {
            return;
        }
        String key = ask("Idempotency key [optional, Enter = skip]");
        ApiResponse response = api.breakDownTask(id, key);
        if (report(response)) {
            System.out.println(body(response).get("count") + " subtask(s) were created under task #" + id + ":");
            printTable(items(response, "subtasks"));
        }
    }

    private static void tasksByPriority() {
        title("Tasks by priority");
        ApiResponse response = api.listByPriority(ask("Priority (LOW / MEDIUM / HIGH)"));
        if (report(response)) {
            printTable(items(response, "items"));
        }
    }

    // =====================================================================
    // Output helpers
    // =====================================================================

    private static void printWelcome(boolean hasKey) {
        System.out.println(LINE);
        System.out.println(" TASK MANAGER - interactive console");
        System.out.println(LINE);
        System.out.println(" Every option calls the real TaskApi and shows what happened.");
        System.out.println(" Your answers are sent as typed, so you can enter wrong values on purpose");
        System.out.println(" to see the validation errors. Option 14 explains every status code.");
        System.out.println(" AI features: " + (hasKey
                ? "ON (LLM_API_KEY found, real model)"
                : "OFFLINE (no LLM_API_KEY, rule-based parser is used)"));
    }

    private static void printMenu() {
        System.out.println();
        System.out.println(LINE);
        System.out.println(" TASKS                      SUBTASKS                 AI");
        System.out.println("  1  Add a task              8  Add a subtask         10  Task from a sentence");
        System.out.println("  2  View one task           9  View subtasks         11  Break a task into subtasks");
        System.out.println("  3  View all tasks");
        System.out.println("  4  Search / filter / sort  OTHER");
        System.out.println("  5  Edit a task            12  Tasks by priority");
        System.out.println("  6  Mark a task as done    13  Show/hide raw JSON (now " + (showJson ? "shown" : "hidden") + ")");
        System.out.println("  7  Delete a task          14  Help: what the codes mean");
        System.out.println("                             0  Exit");
        System.out.println(LINE);
    }

    private static void printHelp() {
        title("What the results mean");
        System.out.println(" 200 OK               The request worked.");
        System.out.println(" 201 Created          Something new was saved.");
        System.out.println(" 400 Invalid input    A value broke a rule. The list under it says which field and why.");
        System.out.println(" 404 Not found        No task has that id.");
        System.out.println(" 422 Key conflict     That idempotency key was already used for a DIFFERENT request.");
        System.out.println(" 500 Internal error   Unexpected problem inside the program.");
        System.out.println(" 502 Bad AI answer    The AI failed, timed out, or answered with something invalid. Nothing was saved.");
        System.out.println(" 503 AI unavailable   LLM_API_KEY is not set for the real-model mode.");
        System.out.println();
        System.out.println(" Rules: title and description must not be empty | done must be exactly true or false");
        System.out.println("        due date must be a real date YYYY-MM-DD (2026-02-30 is rejected)");
        System.out.println("        priority must be exactly LOW, MEDIUM or HIGH (uppercase)");
        System.out.println("        a subtask cannot have its own subtasks | deleting a task deletes its subtasks");
        System.out.println(" Idempotency: send the same request twice with the same key -> the same result, nothing duplicated.");
    }

    /** Prints a one-line verdict (and error details). Returns true when the call succeeded. */
    private static boolean report(ApiResponse response) {
        int status = response.getStatus();
        if (response.isSuccess()) {
            System.out.println("[OK] " + status + " " + statusName(status));
        } else {
            Map<String, Object> error = map(body(response).get("error"));
            System.out.println("[FAILED] " + status + " " + statusName(status) + " - " + error.get("message"));
            List<Map<String, Object>> details = list(error.get("details"));
            for (Map<String, Object> d : details) {
                System.out.println("   * " + d.get("field") + ": " + d.get("issue"));
            }
            String hint = hint(status);
            if (hint != null) {
                System.out.println("   Tip: " + hint);
            }
        }
        if (showJson) {
            System.out.println(response.toJson());
        }
        return response.isSuccess();
    }

    private static String statusName(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 400 -> "Invalid input";
            case 404 -> "Not found";
            case 422 -> "Idempotency key conflict";
            case 500 -> "Internal error";
            case 502 -> "Bad AI answer";
            case 503 -> "AI unavailable";
            default -> "";
        };
    }

    private static String hint(int status) {
        return switch (status) {
            case 400 -> "fix the field(s) listed above and try again.";
            case 404 -> "use option 3 to see which task ids exist.";
            case 422 -> "use a new idempotency key, or send exactly the same values as before.";
            case 502 -> "the AI answer was rejected and nothing was saved. Try again or rephrase.";
            case 503 -> "set LLM_API_KEY in the run configuration to use the real model.";
            default -> null;
        };
    }

    private static void printCard(Map<String, Object> task) {
        System.out.println("   #" + task.get("id") + "  " + task.get("title"));
        System.out.println("      Description : " + task.get("description"));
        System.out.println("      Done        : " + (Boolean.TRUE.equals(task.get("completed")) ? "yes" : "no"));
        System.out.println("      Due date    : " + orNone(task.get("dueDate")));
        System.out.println("      Priority    : " + task.get("priority"));
        if (task.get("recurrence") != null) {
            System.out.println("      Repeats     : " + task.get("recurrence"));
        }
        if (task.get("parentId") != null) {
            System.out.println("      Subtask of  : #" + task.get("parentId"));
        }
    }

    private static void printTable(List<Map<String, Object>> tasks) {
        if (tasks.isEmpty()) {
            System.out.println("   (no tasks)");
            return;
        }
        String format = "   %-4s %-32s %-5s %-11s %-8s %-7s%n";
        System.out.printf(format, "ID", "TITLE", "DONE", "DUE", "PRIORITY", "PARENT");
        System.out.printf(format, "--", "-----", "----", "---", "--------", "------");
        for (Map<String, Object> t : tasks) {
            System.out.printf(format,
                    t.get("id"),
                    shorten(String.valueOf(t.get("title")), 32),
                    Boolean.TRUE.equals(t.get("completed")) ? "yes" : "no",
                    orNone(t.get("dueDate")),
                    t.get("priority"),
                    t.get("parentId") == null ? "-" : "#" + t.get("parentId"));
        }
    }

    private static void title(String text) {
        System.out.println("== " + text + " ==");
    }

    // =====================================================================
    // Input helpers
    // =====================================================================

    /** Asks for the fields of a new task. Values are passed as typed, so validation can be tested. */
    private static TaskRequest readTaskFields() {
        return new TaskRequest(
                ask("Title (required), e.g. Buy milk"),
                ask("Description (required), e.g. 2 litres from the corner shop"),
                ask("Already done? true/false [Enter = false]"),
                ask("Due date YYYY-MM-DD, e.g. 2026-12-01 [Enter = no date]"),
                ask("Priority LOW / MEDIUM / HIGH [Enter = MEDIUM]"));
    }

    /** Prints a prompt; returns the trimmed answer, or null if the user just pressed Enter. */
    private static String ask(String prompt) {
        System.out.print(prompt + ": ");
        if (!IN.hasNextLine()) {
            System.out.println();
            System.exit(0);
        }
        String line = IN.nextLine().trim();
        return line.isEmpty() ? null : line;
    }

    private static Integer askId(String prompt) {
        Integer id = askNumber(prompt);
        if (id == null) {
            System.out.println("No id entered. Back to the menu.");
        }
        return id;
    }

    private static Integer askNumber(String prompt) {
        while (true) {
            String answer = ask(prompt);
            if (answer == null) {
                return null;
            }
            try {
                return Integer.parseInt(answer);
            } catch (NumberFormatException e) {
                System.out.println("Please type a whole number (or press Enter to skip).");
            }
        }
    }

    private static String keep(String answer, String current) {
        return answer == null ? current : answer;
    }

    // =====================================================================
    // Small JSON-map helpers (response bodies are Maps and Lists)
    // =====================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ApiResponse response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static List<Map<String, Object>> items(ApiResponse response, String key) {
        return list(body(response).get(key));
    }

    private static String text(Map<String, Object> task, String key) {
        Object value = task.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String orNone(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
