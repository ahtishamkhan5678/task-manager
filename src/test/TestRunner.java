package test;

import api.ApiResponse;
import api.IdempotencyStore;
import api.TaskApi;
import event.AuditTrailListener;
import event.TaskEventListener;
import event.TaskEvents;
import llm.FakeLlmClient;
import llm.FaultyLlmClient;
import llm.FaultyLlmClient.Fault;
import llm.LlmClient;
import model.Task;
import model.TaskRequest;
import parser.BreakdownStrategy;
import parser.ParserFactory;
import parser.RuleBasedBreakdownStrategy;
import parser.TaskParser;
import query.HasDueDateFilter;
import query.TaskQuery;
import query.TitleSort;
import repository.InMemoryTaskRepository;
import repository.TaskRepository;
import service.TaskService;
import service.TaskValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Plain-Java test runner (no test library). Prints PASS/FAIL per case and exits with code 1 on any failure.
 * Uses only FakeLlmClient / FaultyLlmClient / rule-based strategies: no API key and no internet needed.
 */
public class TestRunner {

    private static final String TODAY = "2026-11-15";
    private static final String VALID_LLM_JSON =
            "{\"title\":\"Pay rent\",\"description\":\"Pay the monthly rent\",\"dueDate\":\"2026-12-01\",\"recurrence\":\"MONTHLY\"}";
    private static final String VALID_BREAKDOWN =
            "Book venue|Call three venues and compare prices\nSend invitations|Email all guests\nOrder cake|Order a chocolate cake";

    private static final TaskRepository repo = InMemoryTaskRepository.getInstance();
    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("Running TestRunner (offline, fake LLM clients)\n");

        group("1. CRUD");
        createTaskReturns201WithDefaults();
        getTaskReturns200();
        getUnknownTaskReturns404();
        updateTaskReturns200AndKeepsCreatedAt();
        updateUnknownTaskReturns404();
        updateWithInvalidInputReturns400();
        deleteTaskReturns200ThenGetReturns404();
        deleteUnknownTaskReturns404();

        group("2. Validation and error shape");
        emptyTitleReturns400();
        blankDescriptionReturns400();
        severalProblemsReportedTogether();
        completedMustBeExactlyTrueOrFalse();
        impossibleDateRejected();
        leapYearRules();
        badDateFormatsRejected();
        priorityMustBeExact();
        nullRequestReturns400();
        errorShapeIsExact();
        unexpectedExceptionBecomes500InSameShape();

        group("3. AI task creation (LlmTaskParser with fakes)");
        fakeLlmCreatesTask();
        promptContainsTodayAndText();
        llmReplyWrappedInCodeFenceIsAccepted();
        faultyOutputGives502AndSavesNothing(Fault.EMPTY_TITLE, "empty title");
        faultyOutputGives502AndSavesNothing(Fault.IMPOSSIBLE_DATE, "impossible date");
        faultyOutputGives502AndSavesNothing(Fault.WRONG_TYPE, "wrong type (title is a number)");
        faultyOutputGives502AndSavesNothing(Fault.MALFORMED_TEXT, "malformed text (not JSON)");
        faultyOutputGives502AndSavesNothing(Fault.EMPTY_REPLY, "empty reply");
        faultyOutputGives502AndSavesNothing(Fault.THROWS, "client throws");
        faultyOutputGives502AndSavesNothing(Fault.TIMEOUT, "client times out");
        invalidRecurrenceFromModelGives502();
        missingApiKeyGives503();
        missingApiKeyGives503ForBreakdown();
        emptyTextGives400WithoutCallingModel();
        ruleBasedParserHandlesRentExample();
        ruleBasedParserHandlesTomorrow();
        parserFactoryAutoPicksRuleBasedWithoutKey();

        group("4. Subtasks and breakdown");
        createSubtaskReturns201WithParentId();
        createSubtaskUnknownParentReturns404();
        subtaskOfSubtaskReturns400();
        listSubtasksReturnsOnlyChildren();
        listSubtasksOfUnknownParentReturns404();
        llmBreakdownCreatesSubtasks();
        breakdownWithOneBadLineSavesNothing();
        breakdownWithEmptyTitleItemSavesNothing();
        breakdownWhenClientThrowsGives502();
        breakdownOfSubtaskReturns400WithoutCallingModel();
        ruleBasedBreakdownSplitsOnAndAndCommas();
        deleteParentDeletesSubtasks();

        group("5. Idempotency");
        sameKeySameRequestCreatesOnce();
        sameKeyDifferentRequestReturns422();
        repeatedKeyNeverCallsParserTwice();
        repeatedKeyNeverCallsBreakdownTwice();
        failedResponseIsNotStored();
        nullKeyCreatesEveryTime();
        blankKeyReturns400();
        sameKeyAcrossDifferentOperationsReturns422();
        concurrentRetriesCreateOnlyOneTask();

        group("6. Filtering, search, sorting, pagination");
        filterByCompleted();
        filterByPriority();
        searchIsCaseInsensitiveOnTitleOrDescription();
        sortByDueDateDescPutsNoDateLast();
        sortByCreatedAtDesc();
        paginationSplitsPages();
        defaultPageSizeIsTen();
        pageBeyondLastReturnsEmptyList();
        invalidQueryValuesReturn400();
        allFeaturesCombinedInOneCall();
        listByPriorityValidAndInvalid();
        newFilterAndSortWithoutEditingExistingClasses();

        group("7. Observer events");
        eventsAreFiredForCreateUpdateCompleteDelete();
        removedListenerGetsNoMoreEvents();
        brokenListenerDoesNotBreakTheOperation();

        group("8. Gaps found in the critique (round 2)");
        completeTaskUnknownReturns404AndSecondCallIsHarmless();
        deletingSubtaskKeepsParent();
        updatingSubtaskKeepsParentId();
        listTasksWithNullQueryUsesDefaults();
        blankSearchIsIgnored();
        simpleJsonReadsEscapesUnicodeAndNesting();
        simpleJsonRejectsMalformedInput();
        ruleBasedParserHandlesNextFridayAndShortMonths();
        breakdownStripsBulletsAndRejectsDoublePipe();
        breakdownWithTooManyLinesIsRejected();
        sameKeyDifferentTextReturns422();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed, " + (passed + failed) + " total");
        if (!failures.isEmpty()) {
            System.out.println("Failed cases:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    // =====================================================================
    // 1. CRUD
    // =====================================================================

    static void createTaskReturns201WithDefaults() {
        TaskApi api = offlineApi();
        ApiResponse r = api.createTask(req("Buy milk", "2 litres"), null);
        check("createTask valid -> 201, id 1, default priority MEDIUM, completed false",
                r.getStatus() == 201 && intField(r, "id") == 1
                        && "MEDIUM".equals(field(r, "priority")) && Boolean.FALSE.equals(field(r, "completed")), r);
    }

    static void getTaskReturns200() {
        TaskApi api = offlineApi();
        api.createTask(req("A", "a"), null);
        ApiResponse r = api.getTask(1);
        check("getTask existing -> 200 with the task", r.getStatus() == 200 && "A".equals(field(r, "title")), r);
    }

    static void getUnknownTaskReturns404() {
        ApiResponse r = offlineApi().getTask(42);
        check("getTask unknown id -> 404 NOT_FOUND", r.getStatus() == 404 && "NOT_FOUND".equals(errorCode(r)), r);
    }

    static void updateTaskReturns200AndKeepsCreatedAt() {
        TaskApi api = offlineApi();
        long createdAt = ((Number) body(api.createTask(req("Old", "old"), null)).get("createdAt")).longValue();
        ApiResponse r = api.updateTask(1, new TaskRequest("New", "new", "true", "2026-12-01", "HIGH"));
        check("updateTask -> 200, fields changed, id and createdAt kept",
                r.getStatus() == 200 && "New".equals(field(r, "title")) && "HIGH".equals(field(r, "priority"))
                        && intField(r, "id") == 1 && ((Number) field(r, "createdAt")).longValue() == createdAt, r);
    }

    static void updateUnknownTaskReturns404() {
        ApiResponse r = offlineApi().updateTask(99, req("x", "y"));
        check("updateTask unknown id -> 404", r.getStatus() == 404, r);
    }

    static void updateWithInvalidInputReturns400() {
        TaskApi api = offlineApi();
        api.createTask(req("A", "a"), null);
        ApiResponse r = api.updateTask(1, new TaskRequest("", "a", "false", null, null));
        check("updateTask invalid input -> 400 and task unchanged",
                r.getStatus() == 400 && repo.findById(1).get().getTitle().equals("A"), r);
    }

    static void deleteTaskReturns200ThenGetReturns404() {
        TaskApi api = offlineApi();
        api.createTask(req("A", "a"), null);
        ApiResponse r = api.deleteTask(1);
        check("deleteTask -> 200, then getTask -> 404",
                r.getStatus() == 200 && api.getTask(1).getStatus() == 404, r);
    }

    static void deleteUnknownTaskReturns404() {
        ApiResponse r = offlineApi().deleteTask(7);
        check("deleteTask unknown id -> 404", r.getStatus() == 404, r);
    }

    // =====================================================================
    // 2. Validation and error shape
    // =====================================================================

    static void emptyTitleReturns400() {
        ApiResponse r = offlineApi().createTask(req("", "desc"), null);
        check("empty title -> 400 VALIDATION_ERROR on field title",
                r.getStatus() == 400 && "VALIDATION_ERROR".equals(errorCode(r)) && hasDetail(r, "title"), r);
    }

    static void blankDescriptionReturns400() {
        ApiResponse r = offlineApi().createTask(req("Title", "   "), null);
        check("blank description (spaces only) -> 400 on field description",
                r.getStatus() == 400 && hasDetail(r, "description"), r);
    }

    static void severalProblemsReportedTogether() {
        ApiResponse r = offlineApi().createTask(new TaskRequest("", "", "maybe", "2026-13-01", "urgent"), null);
        check("five bad fields -> one 400 with five details",
                r.getStatus() == 400 && details(r).size() == 5, r);
    }

    static void completedMustBeExactlyTrueOrFalse() {
        TaskApi api = offlineApi();
        boolean yes = api.createTask(new TaskRequest("T", "D", "yes", null, null), null).getStatus() == 400;
        boolean upper = api.createTask(new TaskRequest("T", "D", "TRUE", null, null), null).getStatus() == 400;
        boolean empty = api.createTask(new TaskRequest("T", "D", "", null, null), null).getStatus() == 400;
        boolean ok = api.createTask(new TaskRequest("T", "D", "true", null, null), null).getStatus() == 201;
        check("completed: 'yes', 'TRUE', '' rejected; 'true' accepted", yes && upper && empty && ok, null);
    }

    static void impossibleDateRejected() {
        ApiResponse r = offlineApi().createTask(new TaskRequest("T", "D", "false", "2026-02-30", null), null);
        check("dueDate 2026-02-30 -> 400 on field dueDate", r.getStatus() == 400 && hasDetail(r, "dueDate"), r);
    }

    static void leapYearRules() {
        boolean ok = TaskValidator.isValidDate("2028-02-29")
                && TaskValidator.isValidDate("2000-02-29")
                && !TaskValidator.isValidDate("2025-02-29")
                && !TaskValidator.isValidDate("1900-02-29")
                && TaskValidator.isValidDate("2026-04-30")
                && !TaskValidator.isValidDate("2026-04-31")
                && TaskValidator.isValidDate("2026-12-31");
        check("leap years: 2028/2000 Feb 29 valid, 2025/1900 Feb 29 invalid, Apr 31 invalid", ok, null);
    }

    static void badDateFormatsRejected() {
        String[] bad = {"2026-1-5", "15/11/2026", "2026/11/15", "20261115", "2026-11-1a", "", " ", "2026-00-10", "0000-01-01"};
        boolean allRejected = true;
        for (String d : bad) {
            if (TaskValidator.isValidDate(d)) {
                allRejected = false;
            }
        }
        check("bad date formats rejected (2026-1-5, 15/11/2026, empty, month 00, year 0000...)", allRejected, null);
    }

    static void priorityMustBeExact() {
        TaskApi api = offlineApi();
        boolean urgent = api.createTask(new TaskRequest("T", "D", "false", null, "URGENT"), null).getStatus() == 400;
        boolean lower = api.createTask(new TaskRequest("T", "D", "false", null, "low"), null).getStatus() == 400;
        boolean high = api.createTask(new TaskRequest("T", "D", "false", null, "HIGH"), null).getStatus() == 201;
        check("priority: 'URGENT' and 'low' rejected, 'HIGH' accepted", urgent && lower && high, null);
    }

    static void nullRequestReturns400() {
        ApiResponse r = offlineApi().createTask(null, null);
        check("createTask(null) -> 400 not a crash", r.getStatus() == 400, r);
    }

    static void errorShapeIsExact() {
        ApiResponse r = offlineApi().createTask(req("", "d"), null);
        Map<String, Object> b = body(r);
        Map<String, Object> error = map(b.get("error"));
        Map<String, Object> firstDetail = map(details(r).get(0));
        boolean ok = b.size() == 1
                && error.size() == 3
                && error.get("code") instanceof String
                && error.get("message") instanceof String
                && error.get("details") instanceof List
                && firstDetail.keySet().equals(java.util.Set.of("field", "issue"));
        check("error body is exactly {error:{code:String, message:String, details:[{field,issue}]}}", ok, r);
    }

    static void unexpectedExceptionBecomes500InSameShape() {
        TaskRepository broken = new TaskRepository() {
            public Task save(Task task) { throw new IllegalStateException("disk on fire"); }
            public Optional<Task> findById(int id) { throw new IllegalStateException("disk on fire"); }
            public List<Task> findAll() { throw new IllegalStateException("disk on fire"); }
            public boolean delete(int id) { return false; }
            public void clear() { }
        };
        TaskApi api = new TaskApi(new TaskService(broken, new FixedParser(), new RuleBasedBreakdownStrategy(), () -> TODAY));
        ApiResponse r = api.createTask(req("A", "a"), null);
        check("unexpected exception -> 500 INTERNAL_ERROR in the same error shape",
                r.getStatus() == 500 && "INTERNAL_ERROR".equals(errorCode(r)) && details(r).isEmpty(), r);
    }

    // =====================================================================
    // 3. AI task creation
    // =====================================================================

    static void fakeLlmCreatesTask() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);
        ApiResponse r = apiWith(fake).createTaskFromText("Remind me to pay rent on the 1st of every month", null);
        check("FakeLlmClient valid JSON -> 201 with title, dueDate, recurrence MONTHLY",
                r.getStatus() == 201 && "Pay rent".equals(field(r, "title"))
                        && "2026-12-01".equals(field(r, "dueDate")) && "MONTHLY".equals(field(r, "recurrence")), r);
    }

    static void promptContainsTodayAndText() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);
        apiWith(fake).createTaskFromText("pay rent next Friday", null);
        String prompt = fake.getLastPrompt();
        check("prompt sent to the model contains today's date and the user's text",
                prompt != null && prompt.contains(TODAY) && prompt.contains("pay rent next Friday"), null);
    }

    static void llmReplyWrappedInCodeFenceIsAccepted() {
        FakeLlmClient fake = new FakeLlmClient("```json\n" + VALID_LLM_JSON + "\n```");
        ApiResponse r = apiWith(fake).createTaskFromText("pay rent", null);
        check("reply wrapped in ```json fences is still read correctly", r.getStatus() == 201, r);
    }

    static void faultyOutputGives502AndSavesNothing(Fault fault, String label) {
        FaultyLlmClient faulty = new FaultyLlmClient(fault);
        ApiResponse r = apiWith(faulty).createTaskFromText("pay rent on the 1st", null);
        check("bad model output (" + label + ") -> 502 and nothing saved",
                r.getStatus() == 502 && repo.findAll().isEmpty(), r);
    }

    static void invalidRecurrenceFromModelGives502() {
        FakeLlmClient fake = new FakeLlmClient(
                "{\"title\":\"Gym\",\"description\":\"Go to gym\",\"dueDate\":null,\"recurrence\":\"every tuesday\"}");
        ApiResponse r = apiWith(fake).createTaskFromText("gym every tuesday", null);
        check("model returns unknown recurrence -> 502 and nothing saved",
                r.getStatus() == 502 && hasDetail(r, "recurrence") && repo.findAll().isEmpty(), r);
    }

    static void missingApiKeyGives503() {
        reset();
        TaskParser parser = ParserFactory.createTaskParser(ParserFactory.Mode.LLM, null);
        TaskApi api = new TaskApi(new TaskService(repo, parser, new RuleBasedBreakdownStrategy(), () -> TODAY));
        ApiResponse r = api.createTaskFromText("pay rent", null);
        check("Mode.LLM with no LLM_API_KEY -> 503 LLM_UNAVAILABLE (no crash, nothing saved)",
                r.getStatus() == 503 && "LLM_UNAVAILABLE".equals(errorCode(r)) && repo.findAll().isEmpty(), r);
    }

    static void missingApiKeyGives503ForBreakdown() {
        reset();
        BreakdownStrategy strategy = ParserFactory.createBreakdownStrategy(ParserFactory.Mode.LLM, null);
        TaskApi api = new TaskApi(new TaskService(repo, new FixedParser(), strategy, () -> TODAY));
        api.createTask(req("Party", "p"), null);
        ApiResponse r = api.breakDownTask(1, null);
        check("breakDownTask in Mode.LLM with no LLM_API_KEY -> 503 and no subtasks saved",
                r.getStatus() == 503 && "LLM_UNAVAILABLE".equals(errorCode(r)) && repo.findAll().size() == 1, r);
    }

    static void emptyTextGives400WithoutCallingModel() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);
        ApiResponse r = apiWith(fake).createTaskFromText("   ", null);
        check("blank text -> 400 and the model is never called",
                r.getStatus() == 400 && fake.getCallCount() == 0, r);
    }

    static void ruleBasedParserHandlesRentExample() {
        TaskApi api = offlineApi();
        ApiResponse r = api.createTaskFromText("Remind me to pay rent on the 1st of every month", null);
        check("RuleBasedTaskParser: 'pay rent on the 1st of every month' -> 'Pay rent', 2026-12-01, MONTHLY",
                r.getStatus() == 201 && "Pay rent".equals(field(r, "title"))
                        && "2026-12-01".equals(field(r, "dueDate")) && "MONTHLY".equals(field(r, "recurrence")), r);
    }

    static void ruleBasedParserHandlesTomorrow() {
        ApiResponse r = offlineApi().createTaskFromText("Call the dentist tomorrow", null);
        check("RuleBasedTaskParser: 'tomorrow' -> 2026-11-16",
                r.getStatus() == 201 && "2026-11-16".equals(field(r, "dueDate"))
                        && "Call the dentist".equals(field(r, "title")), r);
    }

    static void parserFactoryAutoPicksRuleBasedWithoutKey() {
        boolean noKey = ParserFactory.createTaskParser(ParserFactory.Mode.AUTO, "") instanceof parser.RuleBasedTaskParser;
        boolean withKey = ParserFactory.createTaskParser(ParserFactory.Mode.AUTO, "dummy-key") instanceof parser.LlmTaskParser;
        check("ParserFactory AUTO: rule-based without key, LLM parser with key (no network call made)", noKey && withKey, null);
    }

    // =====================================================================
    // 4. Subtasks and breakdown
    // =====================================================================

    static void createSubtaskReturns201WithParentId() {
        TaskApi api = offlineApi();
        api.createTask(req("Parent", "p"), null);
        ApiResponse r = api.createSubtask(1, req("Child", "c"));
        check("createSubtask -> 201 with parentId set", r.getStatus() == 201 && intField(r, "parentId") == 1, r);
    }

    static void createSubtaskUnknownParentReturns404() {
        ApiResponse r = offlineApi().createSubtask(55, req("Child", "c"));
        check("createSubtask unknown parent -> 404", r.getStatus() == 404, r);
    }

    static void subtaskOfSubtaskReturns400() {
        TaskApi api = offlineApi();
        api.createTask(req("Parent", "p"), null);
        api.createSubtask(1, req("Child", "c"));
        ApiResponse r = api.createSubtask(2, req("Grandchild", "g"));
        check("subtask of a subtask -> 400 and not saved",
                r.getStatus() == 400 && repo.findAll().size() == 2, r);
    }

    static void listSubtasksReturnsOnlyChildren() {
        TaskApi api = offlineApi();
        api.createTask(req("P1", "p"), null);
        api.createTask(req("P2", "p"), null);
        api.createSubtask(1, req("C1", "c"));
        api.createSubtask(1, req("C2", "c"));
        api.createSubtask(2, req("Other", "c"));
        ApiResponse r = api.listSubtasks(1);
        check("listSubtasks returns only that parent's children", r.getStatus() == 200 && intField(r, "count") == 2, r);
    }

    static void listSubtasksOfUnknownParentReturns404() {
        ApiResponse r = offlineApi().listSubtasks(321);
        check("listSubtasks unknown parent -> 404", r.getStatus() == 404, r);
    }

    static void llmBreakdownCreatesSubtasks() {
        FakeLlmClient fake = new FakeLlmClient(VALID_BREAKDOWN);
        TaskApi api = apiWithBreakdown(fake);
        api.createTask(new TaskRequest("Organise party", "Birthday party", "false", "2026-12-20", "HIGH"), null);
        ApiResponse r = api.breakDownTask(1, null);
        boolean inherits = repo.findById(2).map(t -> "2026-12-20".equals(t.getDueDate())
                && t.getPriority().name().equals("HIGH") && t.getParentId() == 1).orElse(false);
        check("LlmBreakdownStrategy (fake) -> 201, 3 subtasks inheriting dueDate and priority",
                r.getStatus() == 201 && intField(r, "count") == 3 && repo.findAll().size() == 4 && inherits, r);
    }

    static void breakdownWithOneBadLineSavesNothing() {
        TaskApi api = apiWithBreakdown(new FaultyLlmClient(Fault.BAD_BREAKDOWN_LINE));
        api.createTask(req("Organise party", "p"), null);
        ApiResponse r = api.breakDownTask(1, null);
        check("breakdown with a malformed line -> 502 and NO subtasks saved",
                r.getStatus() == 502 && repo.findAll().size() == 1, r);
    }

    static void breakdownWithEmptyTitleItemSavesNothing() {
        TaskApi api = apiWithBreakdown(new FakeLlmClient("Book venue|Call venues\n  |Missing title here"));
        api.createTask(req("Organise party", "p"), null);
        ApiResponse r = api.breakDownTask(1, null);
        check("breakdown where one item has an empty title -> 502, details point at subtasks[1].title, none saved",
                r.getStatus() == 502 && hasDetail(r, "subtasks[1].title") && repo.findAll().size() == 1, r);
    }

    static void breakdownWhenClientThrowsGives502() {
        TaskApi api = apiWithBreakdown(new FaultyLlmClient(Fault.THROWS));
        api.createTask(req("Organise party", "p"), null);
        ApiResponse r = api.breakDownTask(1, null);
        check("breakdown when model call throws -> 502 LLM_CALL_FAILED",
                r.getStatus() == 502 && "LLM_CALL_FAILED".equals(errorCode(r)) && repo.findAll().size() == 1, r);
    }

    static void breakdownOfSubtaskReturns400WithoutCallingModel() {
        FakeLlmClient fake = new FakeLlmClient(VALID_BREAKDOWN);
        TaskApi api = apiWithBreakdown(fake);
        api.createTask(req("Parent", "p"), null);
        api.createSubtask(1, req("Child", "c"));
        ApiResponse r = api.breakDownTask(2, null);
        check("breakDownTask on a subtask -> 400 and the model is never called",
                r.getStatus() == 400 && fake.getCallCount() == 0, r);
    }

    static void ruleBasedBreakdownSplitsOnAndAndCommas() {
        TaskApi api = offlineApi();
        api.createTask(req("Move house", "Pack boxes, hire a van and clean the old flat"), null);
        ApiResponse r = api.breakDownTask(1, null);
        List<String> titles = new ArrayList<>();
        for (Task t : repo.findAll()) {
            if (t.isSubtask()) {
                titles.add(t.getTitle());
            }
        }
        check("RuleBasedBreakdownStrategy splits on commas and 'and' -> 3 subtasks",
                r.getStatus() == 201 && titles.equals(List.of("Pack boxes", "Hire a van", "Clean the old flat")), r);
    }

    static void deleteParentDeletesSubtasks() {
        TaskApi api = offlineApi();
        api.createTask(req("Parent", "p"), null);
        api.createTask(req("Keep me", "k"), null);
        api.createSubtask(1, req("C1", "c"));
        api.createSubtask(1, req("C2", "c"));
        ApiResponse r = api.deleteTask(1);
        check("deleteTask on parent removes parent + its 2 subtasks, keeps others",
                r.getStatus() == 200 && intField(r, "deletedCount") == 3
                        && repo.findAll().size() == 1 && repo.findAll().get(0).getTitle().equals("Keep me"), r);
    }

    // =====================================================================
    // 5. Idempotency
    // =====================================================================

    static void sameKeySameRequestCreatesOnce() {
        TaskApi api = offlineApi();
        ApiResponse first = api.createTask(req("Pay rent", "r"), "k1");
        ApiResponse second = api.createTask(req("Pay rent", "r"), "k1");
        check("same key + same request twice -> same response, ONE task in repository",
                first.getStatus() == 201 && second.getStatus() == 201
                        && intField(first, "id") == intField(second, "id") && repo.findAll().size() == 1, second);
    }

    static void sameKeyDifferentRequestReturns422() {
        TaskApi api = offlineApi();
        api.createTask(req("Pay rent", "r"), "k1");
        ApiResponse r = api.createTask(req("Pay water bill", "w"), "k1");
        check("same key + different request -> 422 IDEMPOTENCY_KEY_REUSED, still one task",
                r.getStatus() == 422 && "IDEMPOTENCY_KEY_REUSED".equals(errorCode(r)) && repo.findAll().size() == 1, r);
    }

    static void repeatedKeyNeverCallsParserTwice() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);
        TaskApi api = apiWith(fake);
        api.createTaskFromText("pay rent on the 1st", "text-1");
        api.createTaskFromText("pay rent on the 1st", "text-1");
        api.createTaskFromText("pay rent on the 1st", "text-1");
        check("createTaskFromText x3 with same key -> fake LLM called once, one task",
                fake.getCallCount() == 1 && repo.findAll().size() == 1, null);
    }

    static void repeatedKeyNeverCallsBreakdownTwice() {
        FakeLlmClient fake = new FakeLlmClient(VALID_BREAKDOWN);
        TaskApi api = apiWithBreakdown(fake);
        api.createTask(req("Party", "p"), null);
        api.breakDownTask(1, "bd-1");
        api.breakDownTask(1, "bd-1");
        check("breakDownTask x2 with same key -> fake LLM called once, still 3 subtasks",
                fake.getCallCount() == 1 && repo.findAll().size() == 4, null);
    }

    static void failedResponseIsNotStored() {
        TaskApi api = offlineApi();
        ApiResponse bad = api.createTask(req("", "d"), "retry-key");
        ApiResponse fixed = api.createTask(req("Fixed", "d"), "retry-key");
        check("failed (400) response is not stored -> fixing input and retrying with same key gives 201",
                bad.getStatus() == 400 && fixed.getStatus() == 201 && IdempotencyStore.getInstance().size() == 1, fixed);
    }

    static void nullKeyCreatesEveryTime() {
        TaskApi api = offlineApi();
        api.createTask(req("A", "a"), null);
        api.createTask(req("A", "a"), null);
        check("null idempotency key -> no deduplication, two tasks", repo.findAll().size() == 2, null);
    }

    static void blankKeyReturns400() {
        ApiResponse r = offlineApi().createTask(req("A", "a"), "   ");
        check("blank idempotency key -> 400 on field idempotencyKey",
                r.getStatus() == 400 && hasDetail(r, "idempotencyKey") && repo.findAll().isEmpty(), r);
    }

    static void sameKeyAcrossDifferentOperationsReturns422() {
        TaskApi api = offlineApi();
        api.createTask(req("Party", "Food and music"), "shared");
        ApiResponse r = api.breakDownTask(1, "shared");
        check("key used by createTask then reused for breakDownTask -> 422",
                r.getStatus() == 422 && repo.findAll().size() == 1, r);
    }

    static void concurrentRetriesCreateOnlyOneTask() {
        TaskApi api = offlineApi();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                api.createTask(req("Concurrent", "c"), "race-key");
            });
        }
        start.countDown();
        pool.shutdown();
        boolean finished;
        try {
            finished = pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            finished = false;
        }
        check("20 threads retry with the same key at once -> exactly one task", finished && repo.findAll().size() == 1, null);
    }

    // =====================================================================
    // 6. Query
    // =====================================================================

    /** 5 tasks with a known mix of fields. */
    private static TaskApi seededApi() {
        TaskApi api = offlineApi();
        api.createTask(new TaskRequest("Pay rent", "Monthly rent", "false", "2026-12-01", "HIGH"), null);
        api.createTask(new TaskRequest("Buy milk", "From the SHOP", "true", "2026-11-20", "LOW"), null);
        api.createTask(new TaskRequest("Call bank", "Ask about rent receipt", "false", null, "MEDIUM"), null);
        api.createTask(new TaskRequest("Book dentist", "Check-up", "false", "2026-11-25", "HIGH"), null);
        api.createTask(new TaskRequest("Clean desk", "Tidy up", "true", "2027-01-10", "MEDIUM"), null);
        return api;
    }

    static void filterByCompleted() {
        ApiResponse r = seededApi().listTasks(TaskQuery.builder().completed(true).build());
        check("filter completed=true -> 2 tasks", intField(r, "totalItems") == 2 && ids(r).equals(List.of(2, 5)), r);
    }

    static void filterByPriority() {
        ApiResponse r = seededApi().listTasks(TaskQuery.builder().priority("HIGH").build());
        check("filter priority=HIGH -> tasks 1 and 4", ids(r).equals(List.of(1, 4)), r);
    }

    static void searchIsCaseInsensitiveOnTitleOrDescription() {
        TaskApi api = seededApi();
        ApiResponse rent = api.listTasks(TaskQuery.builder().search("RENT").build());
        ApiResponse shop = api.listTasks(TaskQuery.builder().search("shop").build());
        check("search 'RENT' matches title or description (1,3); 'shop' matches 'SHOP' (2)",
                ids(rent).equals(List.of(1, 3)) && ids(shop).equals(List.of(2)), rent);
    }

    static void sortByDueDateDescPutsNoDateLast() {
        ApiResponse r = seededApi().listTasks(TaskQuery.builder().sortBy("dueDate").order("desc").build());
        check("sort dueDate desc -> 5,1,4,2 then task without date (3) last", ids(r).equals(List.of(5, 1, 4, 2, 3)), r);
    }

    static void sortByCreatedAtDesc() {
        ApiResponse r = seededApi().listTasks(TaskQuery.builder().sortBy("createdAt").order("desc").build());
        check("sort createdAt desc -> newest first 5,4,3,2,1", ids(r).equals(List.of(5, 4, 3, 2, 1)), r);
    }

    static void paginationSplitsPages() {
        TaskApi api = seededApi();
        ApiResponse p1 = api.listTasks(TaskQuery.builder().page(1).pageSize(2).build());
        ApiResponse p3 = api.listTasks(TaskQuery.builder().page(3).pageSize(2).build());
        check("pageSize 2: page 1 -> [1,2], page 3 -> [5], totalPages 3",
                ids(p1).equals(List.of(1, 2)) && ids(p3).equals(List.of(5)) && intField(p1, "totalPages") == 3, p3);
    }

    static void defaultPageSizeIsTen() {
        TaskApi api = offlineApi();
        for (int i = 1; i <= 12; i++) {
            api.createTask(req("Task " + i, "d"), null);
        }
        ApiResponse r = api.listTasks(TaskQuery.builder().build());
        check("default page size is 10 and page 1 is the first page",
                intField(r, "pageSize") == 10 && ids(r).size() == 10 && ids(r).get(0) == 1 && intField(r, "totalPages") == 2, null);
    }

    static void pageBeyondLastReturnsEmptyList() {
        ApiResponse r = seededApi().listTasks(TaskQuery.builder().page(9).pageSize(2).build());
        check("page beyond the last page -> 200 with empty items and correct totals",
                r.getStatus() == 200 && ids(r).isEmpty() && intField(r, "totalItems") == 5, r);
    }

    static void invalidQueryValuesReturn400() {
        TaskApi api = seededApi();
        boolean page0 = api.listTasks(TaskQuery.builder().page(0).build()).getStatus() == 400;
        boolean size0 = api.listTasks(TaskQuery.builder().pageSize(0).build()).getStatus() == 400;
        boolean huge = api.listTasks(TaskQuery.builder().pageSize(1000).build()).getStatus() == 400;
        boolean sort = api.listTasks(TaskQuery.builder().sortBy("colour").build()).getStatus() == 400;
        boolean order = api.listTasks(TaskQuery.builder().order("sideways").build()).getStatus() == 400;
        boolean prio = api.listTasks(TaskQuery.builder().priority("URGENT").build()).getStatus() == 400;
        check("page 0, pageSize 0/1000, unknown sortBy/order/priority -> 400",
                page0 && size0 && huge && sort && order && prio, null);
    }

    static void allFeaturesCombinedInOneCall() {
        TaskApi api = seededApi();
        api.createTask(new TaskRequest("Rent car", "For the trip", "false", "2026-11-30", "HIGH"), null);
        ApiResponse r = api.listTasks(TaskQuery.builder()
                .completed(false).priority("HIGH").search("rent")
                .sortBy("dueDate").order("asc").page(1).pageSize(1).build());
        check("completed=false + priority=HIGH + search 'rent' + dueDate asc + page 1 size 1 -> [6], total 2",
                ids(r).equals(List.of(6)) && intField(r, "totalItems") == 2 && intField(r, "totalPages") == 2, r);
    }

    static void listByPriorityValidAndInvalid() {
        TaskApi api = seededApi();
        ApiResponse ok = api.listByPriority("MEDIUM");
        ApiResponse bad = api.listByPriority("SUPER");
        check("listByPriority MEDIUM -> 200 with 2; 'SUPER' -> 400",
                ok.getStatus() == 200 && intField(ok, "count") == 2 && bad.getStatus() == 400, bad);
    }

    static void newFilterAndSortWithoutEditingExistingClasses() {
        reset();
        TaskService service = new TaskService(repo, new FixedParser(), new RuleBasedBreakdownStrategy(), () -> TODAY);
        service.registerSortStrategy(new TitleSort());
        TaskApi api = new TaskApi(service);
        api.createTask(new TaskRequest("Zebra", "z", "false", "2026-12-01", null), null);
        api.createTask(new TaskRequest("Apple", "a", "false", null, null), null);
        api.createTask(new TaskRequest("Mango", "m", "false", "2026-12-02", null), null);
        ApiResponse r = api.listTasks(TaskQuery.builder().filter(new HasDueDateFilter()).sortBy("title").build());
        check("plug-in HasDueDateFilter + TitleSort -> Mango(3), Zebra(1)", ids(r).equals(List.of(3, 1)), r);
    }

    // =====================================================================
    // 7. Observer
    // =====================================================================

    static void eventsAreFiredForCreateUpdateCompleteDelete() {
        reset();
        TaskService service = new TaskService(repo, new FixedParser(), new RuleBasedBreakdownStrategy(), () -> TODAY);
        AuditTrailListener audit = new AuditTrailListener();
        service.addListener(audit);
        TaskApi api = new TaskApi(service);
        api.createTask(req("A", "a"), null);
        api.updateTask(1, req("A2", "a"));
        api.completeTask(1);
        api.deleteTask(1);
        api.createTask(req("", "invalid"), null); // failed: must NOT fire an event
        check("audit counts: 1 created, 1 updated, 1 completed, 1 deleted (failed create fires nothing)",
                audit.getCount(TaskEvents.CREATED) == 1 && audit.getCount(TaskEvents.UPDATED) == 1
                        && audit.getCount(TaskEvents.COMPLETED) == 1 && audit.getCount(TaskEvents.DELETED) == 1, null);
    }

    static void removedListenerGetsNoMoreEvents() {
        reset();
        TaskService service = new TaskService(repo, new FixedParser(), new RuleBasedBreakdownStrategy(), () -> TODAY);
        AuditTrailListener audit = new AuditTrailListener();
        service.addListener(audit);
        TaskApi api = new TaskApi(service);
        api.createTask(req("A", "a"), null);
        service.removeListener(audit);
        api.createTask(req("B", "b"), null);
        check("after removeListener, the listener receives no more events", audit.getCount(TaskEvents.CREATED) == 1, null);
    }

    static void brokenListenerDoesNotBreakTheOperation() {
        reset();
        TaskService service = new TaskService(repo, new FixedParser(), new RuleBasedBreakdownStrategy(), () -> TODAY);
        TaskEventListener broken = (type, task) -> {
            throw new RuntimeException("listener bug");
        };
        AuditTrailListener audit = new AuditTrailListener();
        service.addListener(broken);
        service.addListener(audit);
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        ApiResponse r = new TaskApi(service).createTask(req("A", "a"), null);
        System.setErr(err);
        check("a listener that throws does not fail createTask, and later listeners still run",
                r.getStatus() == 201 && audit.getCount(TaskEvents.CREATED) == 1, r);
    }

    // =====================================================================
    // 8. Critique round 2: cases the first suite did not cover
    // =====================================================================

    static void completeTaskUnknownReturns404AndSecondCallIsHarmless() {
        reset();
        TaskService service = new TaskService(repo, new FixedParser(), new RuleBasedBreakdownStrategy(), () -> TODAY);
        AuditTrailListener audit = new AuditTrailListener();
        service.addListener(audit);
        TaskApi api = new TaskApi(service);
        api.createTask(req("A", "a"), null);
        ApiResponse first = api.completeTask(1);
        ApiResponse second = api.completeTask(1);
        ApiResponse unknown = api.completeTask(99);
        check("completeTask -> 200 completed; calling it again changes nothing and fires no 2nd event; unknown id -> 404",
                first.getStatus() == 200 && Boolean.TRUE.equals(field(first, "completed"))
                        && second.getStatus() == 200 && audit.getCount(TaskEvents.COMPLETED) == 1
                        && unknown.getStatus() == 404, unknown);
    }

    static void deletingSubtaskKeepsParent() {
        TaskApi api = offlineApi();
        api.createTask(req("Parent", "p"), null);
        api.createSubtask(1, req("C1", "c"));
        api.createSubtask(1, req("C2", "c"));
        ApiResponse r = api.deleteTask(2);
        check("deleting ONE subtask removes only it; parent and sibling stay",
                r.getStatus() == 200 && intField(r, "deletedCount") == 1
                        && repo.findById(1).isPresent() && repo.findById(3).isPresent(), r);
    }

    static void updatingSubtaskKeepsParentId() {
        TaskApi api = offlineApi();
        api.createTask(req("Parent", "p"), null);
        api.createSubtask(1, req("Child", "c"));
        ApiResponse r = api.updateTask(2, new TaskRequest("Child v2", "c", "true", null, "HIGH"));
        check("updateTask on a subtask keeps its parentId", r.getStatus() == 200 && intField(r, "parentId") == 1, r);
    }

    static void listTasksWithNullQueryUsesDefaults() {
        TaskApi api = seededApi();
        ApiResponse r = api.listTasks(null);
        check("listTasks(null) -> 200 with defaults (page 1, size 10, all 5 tasks)",
                r.getStatus() == 200 && intField(r, "page") == 1 && intField(r, "pageSize") == 10 && ids(r).size() == 5, r);
    }

    static void blankSearchIsIgnored() {
        ApiResponse r = seededApi().listTasks(TaskQuery.builder().search("   ").build());
        check("search of only spaces is ignored (returns all 5), not 'match nothing'", ids(r).size() == 5, r);
    }

    @SuppressWarnings("unchecked")
    static void simpleJsonReadsEscapesUnicodeAndNesting() {
        Map<String, Object> m = util.SimpleJson.parseObject(
                "{\"a\":\"line\\n\\\"quoted\\\" caf\\u00e9\",\"n\":[1,2.5,true,null],\"o\":{\"k\":-3}}");
        List<Object> n = (List<Object>) m.get("n");
        Map<String, Object> o = (Map<String, Object>) m.get("o");
        boolean ok = "line\n\"quoted\" caf\u00e9".equals(m.get("a"))
                && n.size() == 4 && ((Number) n.get(0)).intValue() == 1 && ((Number) n.get(1)).doubleValue() == 2.5
                && Boolean.TRUE.equals(n.get(2)) && n.get(3) == null && ((Number) o.get("k")).intValue() == -3;
        String roundTrip = util.SimpleJson.write(m);
        check("SimpleJson reads escapes, \\u unicode, numbers, booleans, null, nested objects, and writes them back",
                ok && util.SimpleJson.parseObject(roundTrip).equals(m), null);
    }

    static void simpleJsonRejectsMalformedInput() {
        String[] bad = {"", "{", "{\"a\":}", "{\"a\" 1}", "[1,2", "{\"a\":1} trailing", "nul", "{'a':1}"};
        boolean allRejected = true;
        for (String text : bad) {
            try {
                util.SimpleJson.parse(text);
                allRejected = false;
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
        check("SimpleJson throws IllegalArgumentException for 8 kinds of malformed JSON", allRejected, null);
    }

    static void ruleBasedParserHandlesNextFridayAndShortMonths() {
        TaskApi api = offlineApi();
        ApiResponse friday = api.createTaskFromText("Submit report next Friday", null);   // today 2026-11-15 is a Sunday
        ApiResponse day31 = api.createTaskFromText("Pay card bill on the 31st", null);   // Nov has 30 days -> Dec 31
        check("RuleBasedTaskParser: 'next Friday' -> 2026-11-20; '31st' skips 30-day November -> 2026-12-31",
                "2026-11-20".equals(field(friday, "dueDate")) && "2026-12-31".equals(field(day31, "dueDate")), day31);
    }

    static void breakdownStripsBulletsAndRejectsDoublePipe() {
        TaskApi bullets = apiWithBreakdown(new FakeLlmClient("- Book venue|Call venues\n* Order cake|Chocolate"));
        bullets.createTask(req("Party", "p"), null);
        ApiResponse ok = bullets.breakDownTask(1, null);
        boolean stripped = repo.findById(2).map(t -> t.getTitle().equals("Book venue")).orElse(false);

        TaskApi pipes = apiWithBreakdown(new FakeLlmClient("Book venue|Call venues|today\nOrder cake|Chocolate"));
        pipes.createTask(req("Party", "p"), null);
        ApiResponse bad = pipes.breakDownTask(1, null);
        check("breakdown strips '- '/'* ' bullets; a line with two '|' -> 502 and nothing saved",
                ok.getStatus() == 201 && stripped && bad.getStatus() == 502 && repo.findAll().size() == 1, bad);
    }

    static void breakdownWithTooManyLinesIsRejected() {
        StringBuilder reply = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            reply.append("Step ").append(i).append("|Do step ").append(i).append('\n');
        }
        TaskApi api = apiWithBreakdown(new FakeLlmClient(reply.toString()));
        api.createTask(req("Huge", "h"), null);
        ApiResponse r = api.breakDownTask(1, null);
        check("breakdown with 11 lines (limit 10) -> 502 and nothing saved",
                r.getStatus() == 502 && repo.findAll().size() == 1, r);
    }

    static void sameKeyDifferentTextReturns422() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);
        TaskApi api = apiWith(fake);
        api.createTaskFromText("pay rent on the 1st", "t-key");
        ApiResponse r = api.createTaskFromText("pay water bill", "t-key");
        check("createTaskFromText: same key + different text -> 422 and the model is not called again",
                r.getStatus() == 422 && fake.getCallCount() == 1, r);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Clears the singleton stores so every test starts from an empty state. */
    private static void reset() {
        repo.clear();
        IdempotencyStore.getInstance().clear();
    }

    /** Offline API: rule-based parser + rule-based breakdown, fixed "today". */
    private static TaskApi offlineApi() {
        reset();
        return new TaskApi(new TaskService(repo,
                ParserFactory.createTaskParser(ParserFactory.Mode.RULE_BASED),
                ParserFactory.createBreakdownStrategy(ParserFactory.Mode.RULE_BASED),
                () -> TODAY));
    }

    /** API whose task parser is LlmTaskParser over the given (fake) client, plugged in through the Factory. */
    private static TaskApi apiWith(LlmClient client) {
        reset();
        return new TaskApi(new TaskService(repo, ParserFactory.createTaskParser(client),
                new RuleBasedBreakdownStrategy(), () -> TODAY));
    }

    /** API whose breakdown strategy is LlmBreakdownStrategy over the given (fake) client. */
    private static TaskApi apiWithBreakdown(LlmClient client) {
        reset();
        BreakdownStrategy strategy = ParserFactory.createBreakdownStrategy(client);
        return new TaskApi(new TaskService(repo, new FixedParser(), strategy, () -> TODAY));
    }

    /** Trivial parser for tests that don't exercise text parsing. */
    private static final class FixedParser implements TaskParser {
        @Override
        public model.ParsedTask parse(String text, String today) {
            return new model.ParsedTask(text, text, null, null);
        }
    }

    private static TaskRequest req(String title, String description) {
        return new TaskRequest(title, description, "false", null, null);
    }

    private static void group(String name) {
        System.out.println("\n--- " + name + " ---");
    }

    private static void check(String name, boolean condition, ApiResponse response) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL  " + name);
            if (response != null) {
                System.out.println("      got: " + response.toString().replace("\n", "\n      "));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ApiResponse r) {
        return (Map<String, Object>) r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static Object field(ApiResponse r, String name) {
        return body(r).get(name);
    }

    private static int intField(ApiResponse r, String name) {
        Object v = body(r).get(name);
        return v instanceof Number n ? n.intValue() : -1;
    }

    private static String errorCode(ApiResponse r) {
        Object error = body(r).get("error");
        return error == null ? null : (String) map(error).get("code");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiResponse r) {
        Object error = body(r).get("error");
        return error == null ? List.of() : (List<Object>) map(error).get("details");
    }

    private static boolean hasDetail(ApiResponse r, String fieldName) {
        for (Object d : details(r)) {
            if (fieldName.equals(map(d).get("field"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ids(ApiResponse r) {
        List<Integer> ids = new ArrayList<>();
        Object items = body(r).get("items");
        if (items instanceof List<?> list) {
            for (Object o : list) {
                ids.add(((Number) ((Map<String, Object>) o).get("id")).intValue());
            }
        }
        return ids;
    }
}
