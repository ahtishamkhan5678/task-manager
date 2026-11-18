package app;

import api.ApiResponse;
import api.IdempotencyStore;
import api.TaskApi;
import event.AuditTrailListener;
import event.ConsoleLogListener;
import llm.FakeLlmClient;
import llm.FaultyLlmClient;
import model.Task;
import model.TaskRequest;
import parser.BreakdownStrategy;
import parser.ParserFactory;
import parser.TaskParser;
import query.HasDueDateFilter;
import query.TaskQuery;
import query.TitleSort;
import repository.InMemoryTaskRepository;
import repository.TaskRepository;
import service.TaskService;

/**
 * Walks through every step of the brief and prints each ApiResponse.
 * Runs fully offline. If LLM_API_KEY is set, section 4 also calls the real model.
 */
public class Main {

    private static final TaskRepository repository = InMemoryTaskRepository.getInstance();

    public static void main(String[] args) {
        System.out.println("Task Manager started");

        boolean hasKey = ParserFactory.hasKey(System.getenv(ParserFactory.API_KEY_ENV));
        // Default = the LLM strategies. Without a key, every AI call returns 503 (brief: "If it is missing, return 503").
        // The rule-based strategies are only used where we explicitly choose Mode.RULE_BASED below.
        TaskParser parser = ParserFactory.createTaskParser(ParserFactory.Mode.LLM);
        BreakdownStrategy breakdown = ParserFactory.createBreakdownStrategy(ParserFactory.Mode.LLM);
        System.out.println("LLM_API_KEY present: " + hasKey + " -> default strategies: " + parser.getClass().getSimpleName()
                + " and " + breakdown.getClass().getSimpleName()
                + (hasKey ? " (real model)" : " (AI calls will return 503; rule-based is chosen explicitly where shown)"));

        TaskService service = new TaskService(repository, parser, breakdown);
        ConsoleLogListener console = new ConsoleLogListener();
        AuditTrailListener audit = new AuditTrailListener();
        service.addListener(console);
        service.addListener(audit);
        TaskApi api = new TaskApi(service);

        // ------------------------------------------------------------------
        section("STEP 2 - CRUD");
        show("createTask (valid)", api.createTask(
                new TaskRequest("Buy groceries", "Milk, eggs and bread", "false", "2026-11-20", "HIGH"), null));
        show("createTask (defaults: priority MEDIUM, no due date)", api.createTask(
                new TaskRequest("Read a book", "Finish chapter 3", "false", null, null), null));
        api.createTask(new TaskRequest("Plan trip", "Book flights and hotel", "false", "2026-12-15", "LOW"), null);
        show("getTask(1)", api.getTask(1));
        show("updateTask(2) - mark completed", api.updateTask(2,
                new TaskRequest("Read a book", "Finish chapter 3", "true", null, "LOW")));
        show("listTasks (default query)", api.listTasks(TaskQuery.builder().build()));
        show("getTask(999) - unknown id", api.getTask(999));
        show("updateTask(999) - unknown id", api.updateTask(999,
                new TaskRequest("x", "y", "false", null, null)));

        // ------------------------------------------------------------------
        section("STEP 3 - VALIDATION + ONE ERROR SHAPE");
        show("createTask with several problems at once", api.createTask(
                new TaskRequest("", "   ", "yes", "2026-02-30", "URGENT"), null));
        show("createTask with impossible date 2026-02-30", api.createTask(
                new TaskRequest("Pay bill", "Electricity", "false", "2026-02-30", "LOW"), null));
        show("createTask on leap day 2028-02-29 (valid)", api.createTask(
                new TaskRequest("Leap day party", "Celebrate", "false", "2028-02-29", "LOW"), null));

        // ------------------------------------------------------------------
        section("STEP 4 - AI TASK CREATION");
        String sentence = "Remind me to pay rent on the 1st of every month";

        TaskApi llmOnlyApi = new TaskApi(new TaskService(repository,
                ParserFactory.createTaskParser(ParserFactory.Mode.LLM), breakdown));
        show("createTaskFromText with Mode.LLM (real model if key set, 503 if not)",
                llmOnlyApi.createTaskFromText(sentence, null));

        TaskService ruleService = new TaskService(repository,
                ParserFactory.createTaskParser(ParserFactory.Mode.RULE_BASED),
                ParserFactory.createBreakdownStrategy(ParserFactory.Mode.RULE_BASED));
        ruleService.addListener(console);
        TaskApi ruleApi = new TaskApi(ruleService);
        show("createTaskFromText with RuleBasedTaskParser (offline)", ruleApi.createTaskFromText(sentence, null));
        show("createTaskFromText 'Call mom tomorrow' (offline)", ruleApi.createTaskFromText("Call mom tomorrow", null));

        FakeLlmClient fake = new FakeLlmClient(
                "{\"title\":\"Submit tax return\",\"description\":\"File the yearly tax return online\","
                        + "\"dueDate\":\"2027-04-15\",\"recurrence\":\"YEARLY\"}");
        TaskApi fakeApi = new TaskApi(new TaskService(repository, ParserFactory.createTaskParser(fake), breakdown));
        show("createTaskFromText through LlmTaskParser + FakeLlmClient",
                fakeApi.createTaskFromText("File my taxes by April 15 every year", null));

        int before = repository.findAll().size();
        TaskApi faultyApi = new TaskApi(new TaskService(repository,
                ParserFactory.createTaskParser(new FaultyLlmClient(FaultyLlmClient.Fault.IMPOSSIBLE_DATE)), breakdown));
        show("createTaskFromText when the model returns 2026-02-30 (never trust the model)",
                faultyApi.createTaskFromText("Pay rent", null));
        System.out.println("Tasks before: " + before + ", after: " + repository.findAll().size() + " (nothing saved)");

        // ------------------------------------------------------------------
        section("STEP 5 - SUBTASKS + BREAKDOWN");
        ApiResponse party = ruleApi.createTask(new TaskRequest("Organise party",
                "Book venue, send invitations and order cake", "false", "2026-12-20", "HIGH"), null);
        int partyId = id(party);
        show("createSubtask(" + partyId + ")", ruleApi.createSubtask(partyId,
                new TaskRequest("Buy balloons", "Blue and white", "false", null, null)));
        show("breakDownTask(" + partyId + ") with RuleBasedBreakdownStrategy", ruleApi.breakDownTask(partyId, null));
        show("listSubtasks(" + partyId + ")", ruleApi.listSubtasks(partyId));
        int firstSubtask = repository.findAll().stream()
                .filter(t -> t.getParentId() != null && t.getParentId() == partyId).findFirst().get().getId();
        show("createSubtask under a subtask (400)", ruleApi.createSubtask(firstSubtask,
                new TaskRequest("Too deep", "Not allowed", "false", null, null)));
        show("breakDownTask on a subtask (400)", ruleApi.breakDownTask(firstSubtask, null));

        FakeLlmClient fakeBreakdown = new FakeLlmClient(
                "Choose destination|Compare three cities\nBook flights|Find the cheapest return flights\n"
                        + "Book hotel|Reserve a hotel near the centre");
        TaskApi breakdownApi = new TaskApi(new TaskService(repository, parser,
                ParserFactory.createBreakdownStrategy(fakeBreakdown)));
        show("breakDownTask(3) with LlmBreakdownStrategy + FakeLlmClient", breakdownApi.breakDownTask(3, null));
        show("breakDownTask(1) with the default LLM strategy (real model if key set, 503 if not)", api.breakDownTask(1, null));

        System.out.println("\nRepository BEFORE deleteTask(" + partyId + "):");
        printRepository();
        show("deleteTask(" + partyId + ") - cascades to subtasks", ruleApi.deleteTask(partyId));
        System.out.println("Repository AFTER deleteTask(" + partyId + "):");
        printRepository();

        // ------------------------------------------------------------------
        section("STEP 6 - IDEMPOTENCY");
        TaskRequest rent = new TaskRequest("Pay internet bill", "Monthly broadband", "false", "2026-12-05", "MEDIUM");
        int count = repository.findAll().size();
        show("createTask with key 'key-123' (first call)", api.createTask(rent, "key-123"));
        show("createTask with key 'key-123' (retry, same request)", api.createTask(rent, "key-123"));
        System.out.println("Task count went from " + count + " to " + repository.findAll().size() + " (only ONE created)");
        show("createTask with key 'key-123' but a DIFFERENT request (422)", api.createTask(
                new TaskRequest("Something else", "Different", "false", null, null), "key-123"));

        FakeLlmClient countingFake = new FakeLlmClient(
                "{\"title\":\"Water plants\",\"description\":\"Water the plants\",\"dueDate\":null,\"recurrence\":\"WEEKLY\"}");
        TaskApi countingApi = new TaskApi(new TaskService(repository, ParserFactory.createTaskParser(countingFake), breakdown));
        countingApi.createTaskFromText("water the plants every week", "text-key-1");
        countingApi.createTaskFromText("water the plants every week", "text-key-1");
        System.out.println("createTaskFromText called twice with the same key -> model called "
                + countingFake.getCallCount() + " time(s)");

        show("createTask with key 'fix-me' and bad input (400, NOT stored)", api.createTask(
                new TaskRequest("", "desc", "false", null, null), "fix-me"));
        show("createTask with key 'fix-me' after fixing input (201)", api.createTask(
                new TaskRequest("Fixed title", "desc", "false", null, null), "fix-me"));
        System.out.println("Repository after idempotency demo:");
        printRepository();

        // ------------------------------------------------------------------
        section("STEP 7 - FILTER, SEARCH, SORT, PAGINATION");
        show("open tasks + search 'b' + sort dueDate desc + page 1 size 3 (all in ONE call)",
                api.listTasks(TaskQuery.builder().completed(false).search("b")
                        .sortBy("dueDate").order("desc").page(1).pageSize(3).build()));
        show("page beyond the last page", api.listTasks(TaskQuery.builder().page(50).pageSize(10).build()));
        show("listByPriority(\"HIGH\")", api.listByPriority("HIGH"));
        show("listByPriority(\"URGENT\") - invalid level", api.listByPriority("URGENT"));
        show("invalid query values", api.listTasks(TaskQuery.builder().sortBy("colour").order("up").page(0).build()));

        service.registerSortStrategy(new TitleSort());
        show("Open/Closed: new TitleSort + HasDueDateFilter, no existing class edited",
                api.listTasks(TaskQuery.builder().filter(new HasDueDateFilter()).sortBy("title").build()));

        // ------------------------------------------------------------------
        section("STEP 8 - OBSERVER EVENTS");
        System.out.println("Audit counts so far: " + audit.getCounts());
        System.out.println("Removing ConsoleLogListener, then creating a task. No [event] line should appear:");
        service.removeListener(console);
        ApiResponse quiet = api.createTask(new TaskRequest("Silent task", "No console event", "false", null, null), null);
        System.out.println("Created task id " + id(quiet) + " with no console event above.");
        show("completeTask(" + id(quiet) + ")", api.completeTask(id(quiet)));
        api.updateTask(id(quiet), new TaskRequest("Silent task v2", "Renamed", "true", null, "LOW"));
        api.deleteTask(id(quiet));
        System.out.println("Updated and deleted task " + id(quiet) + " too (still no console lines).");
        System.out.println("Audit listener is still attached and saw all four event types: " + audit.getCounts());

        IdempotencyStore.getInstance().clear();
        System.out.println("\nDone.");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println(title);
        System.out.println("=".repeat(70));
    }

    private static void show(String label, ApiResponse response) {
        System.out.println("\n>>> " + label);
        System.out.println(response);
    }

    @SuppressWarnings("unchecked")
    private static int id(ApiResponse response) {
        return ((Number) ((java.util.Map<String, Object>) response.getBody()).get("id")).intValue();
    }

    private static void printRepository() {
        for (Task t : repository.findAll()) {
            System.out.println("  " + t);
        }
        System.out.println("  (" + repository.findAll().size() + " tasks)");
    }
}
