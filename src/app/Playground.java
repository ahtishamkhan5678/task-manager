package app;

import api.TaskApi;
import event.ConsoleLogListener;
import model.TaskRequest;
import parser.ParserFactory;
import query.TaskQuery;
import repository.InMemoryTaskRepository;
import repository.TaskRepository;
import service.TaskService;

/**
 * Scratch class for trying your own inputs (not part of the graded demo).
 * Edit the calls below and press Run.
 *
 * AI mode: uses the real model when LLM_API_KEY is set, otherwise the offline rule-based
 * strategies (chosen explicitly here so you can still play without a key).
 */
public class Playground {

    public static void main(String[] args) {
        TaskRepository repo = InMemoryTaskRepository.getInstance();
        boolean hasKey = ParserFactory.hasKey(System.getenv(ParserFactory.API_KEY_ENV));
        ParserFactory.Mode mode = hasKey ? ParserFactory.Mode.LLM : ParserFactory.Mode.RULE_BASED;
        System.out.println("Playground - AI mode: " + mode + (hasKey ? " (real model)" : " (no key, offline parser)"));

        TaskService service = new TaskService(repo,
                ParserFactory.createTaskParser(mode),
                ParserFactory.createBreakdownStrategy(mode));
        service.addListener(new ConsoleLogListener());
        TaskApi api = new TaskApi(service);

        // ---- 1. Normal create -> expect 201
        show("createTask", api.createTask(
                new TaskRequest("Pay bills", "Electricity and water", "false", "2026-12-05", "HIGH"), null));

        // ---- 2. Invalid create -> expect 400 with 4 problems (description "x" is valid)
        show("createTask (invalid)", api.createTask(
                new TaskRequest("", "x", "maybe", "2026-02-30", "URGENT"), null));

        // ---- 3. Plain English -> task (change the sentence and re-run!)
        show("createTaskFromText", api.createTaskFromText("Submit my assignment next Friday", null));

        // ---- 4. Break task 1 into subtasks -> expect 201
        show("breakDownTask(1)", api.breakDownTask(1, null));

        // ---- 5. Idempotency: 201, same response again, then 422
        TaskRequest gym = new TaskRequest("Gym", "Leg day", "false", null, null);
        show("createTask key=my-key (1st)", api.createTask(gym, "my-key"));
        show("createTask key=my-key (retry)", api.createTask(gym, "my-key"));
        show("createTask key=my-key (different request)", api.createTask(
                new TaskRequest("Swim", "Pool", "false", null, null), "my-key"));

        // ---- 6. Subtask of a subtask -> expect 400 (first subtask of task 1)
        int firstSubtaskId = repo.findAll().stream()
                .filter(t -> t.getParentId() != null && t.getParentId() == 1)
                .findFirst().map(t -> t.getId()).orElse(-1);
        show("createSubtask under subtask " + firstSubtaskId, api.createSubtask(firstSubtaskId,
                new TaskRequest("Too deep", "x", "false", null, null)));

        // ---- 7. Query: open tasks, latest due date first, 2 per page
        show("listTasks", api.listTasks(TaskQuery.builder()
                .completed(false).sortBy("dueDate").order("desc").page(1).pageSize(2).build()));

        // ---- 8. Delete task 1 -> its subtasks go too
        show("deleteTask(1)", api.deleteTask(1));
        System.out.println("Repository now:");
        repo.findAll().forEach(t -> System.out.println("  " + t));
    }

    private static void show(String label, api.ApiResponse response) {
        System.out.println("\n>>> " + label);
        System.out.println(response);
    }
}
