package service;

import event.TaskEventListener;
import event.TaskEvents;
import exception.ApiException;
import exception.BadGatewayException;
import exception.FieldIssue;
import exception.NotFoundException;
import exception.ValidationException;
import model.ParsedTask;
import model.Priority;
import model.Task;
import model.TaskRequest;
import parser.BreakdownStrategy;
import parser.TaskParser;
import query.CompletedFilter;
import query.PageResult;
import query.PriorityFilter;
import query.SearchFilter;
import query.SortStrategy;
import query.SortStrategyRegistry;
import query.TaskFilter;
import query.TaskQuery;
import repository.TaskRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Business logic. Depends only on interfaces (TaskRepository, TaskParser, BreakdownStrategy),
 * which are handed in through the constructor (Dependency Inversion).
 * It is also the Observer "subject": listeners are told about created/updated/completed/deleted tasks.
 */
public class TaskService {
    public static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository repository;
    private final TaskParser parser;
    private final BreakdownStrategy breakdownStrategy;
    private final Supplier<String> today;
    private final TaskValidator validator = new TaskValidator();
    private final SortStrategyRegistry sortStrategies = SortStrategyRegistry.withDefaults();
    private final List<TaskEventListener> listeners = new CopyOnWriteArrayList<>();

    public TaskService(TaskRepository repository, TaskParser parser, BreakdownStrategy breakdownStrategy) {
        this(repository, parser, breakdownStrategy, () -> LocalDate.now().toString());
    }

    /** {@code today} supplies YYYY-MM-DD; tests pass a fixed date so results are repeatable. */
    public TaskService(TaskRepository repository, TaskParser parser, BreakdownStrategy breakdownStrategy,
                       Supplier<String> today) {
        this.repository = repository;
        this.parser = parser;
        this.breakdownStrategy = breakdownStrategy;
        this.today = today;
    }

    // ---------------- Observer ----------------

    public void addListener(TaskEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TaskEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(String eventType, Task task) {
        for (TaskEventListener listener : listeners) {
            try {
                listener.onEvent(eventType, task);
            } catch (RuntimeException e) {
                // A broken listener must never break the operation that already succeeded.
                System.err.println("Listener " + listener.getClass().getSimpleName() + " failed: " + e.getMessage());
            }
        }
    }

    /** Register an extra sort order without editing existing classes (Open/Closed). */
    public void registerSortStrategy(SortStrategy strategy) {
        sortStrategies.register(strategy);
    }

    // ---------------- CRUD ----------------

    public Task createTask(TaskRequest request) {
        Task saved = repository.save(validator.validate(request).build());
        notifyListeners(TaskEvents.CREATED, saved);
        return saved;
    }

    public Task getTask(int id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.task(id));
    }

    /** Full update: same validation as create. id, parentId and createdAt are kept. */
    public Task updateTask(int id, TaskRequest request) {
        Task existing = getTask(id);
        Task updated = validator.validate(request)
                .id(existing.getId())
                .parentId(existing.getParentId())
                .createdAt(existing.getCreatedAt())
                .build();
        repository.save(updated);
        boolean justCompleted = !existing.isCompleted() && updated.isCompleted();
        notifyListeners(justCompleted ? TaskEvents.COMPLETED : TaskEvents.UPDATED, updated);
        return updated;
    }

    /** Marks a task as done. Calling it twice is harmless. */
    public Task completeTask(int id) {
        Task existing = getTask(id);
        if (existing.isCompleted()) {
            return existing;
        }
        Task done = repository.save(existing.toBuilder().completed(true).build());
        notifyListeners(TaskEvents.COMPLETED, done);
        return done;
    }

    /** Deletes the task and all of its subtasks. Returns every deleted task (subtasks first). */
    public List<Task> deleteTask(int id) {
        Task task = getTask(id);
        List<Task> deleted = new ArrayList<>();
        for (Task child : findSubtasks(id)) {
            repository.delete(child.getId());
            deleted.add(child);
        }
        repository.delete(id);
        deleted.add(task);
        for (Task t : deleted) {
            notifyListeners(TaskEvents.DELETED, t);
        }
        return deleted;
    }

    // ---------------- Subtasks ----------------

    public Task createSubtask(int parentId, TaskRequest request) {
        Task parent = getTask(parentId);
        rejectIfSubtask(parent, "parentId");
        Task saved = repository.save(validator.validate(request).parentId(parentId).build());
        notifyListeners(TaskEvents.CREATED, saved);
        return saved;
    }

    public List<Task> listSubtasks(int parentId) {
        getTask(parentId);
        return findSubtasks(parentId);
    }

    private List<Task> findSubtasks(int parentId) {
        List<Task> result = new ArrayList<>();
        for (Task t : repository.findAll()) {
            if (t.getParentId() != null && t.getParentId() == parentId) {
                result.add(t);
            }
        }
        return result;
    }

    private void rejectIfSubtask(Task task, String field) {
        if (task.isSubtask()) {
            throw new ValidationException(field,
                    "task " + task.getId() + " is already a subtask; subtasks cannot have their own subtasks");
        }
    }

    // ---------------- AI features ----------------

    /**
     * Plain English -> task. The parser's output is never trusted: it goes through the same
     * validation as createTask, and anything invalid becomes a 502 with nothing saved.
     */
    public Task createTaskFromText(String text) {
        if (text == null || text.isBlank()) {
            throw new ValidationException("text", "must not be empty");
        }
        ParsedTask parsed = callModel(() -> parser.parse(text, today.get()));
        if (parsed == null) {
            throw BadGatewayException.invalidOutput("The parser returned nothing",
                    List.of(new FieldIssue("reply", "must not be empty")));
        }
        TaskRequest request = new TaskRequest(parsed.getTitle(), parsed.getDescription(), "false",
                parsed.getDueDate(), null, parsed.getRecurrence());
        Task.Builder builder;
        try {
            builder = validator.validate(request);
        } catch (ValidationException e) {
            throw BadGatewayException.invalidOutput("The language model returned an invalid task", e.getDetails());
        }
        Task saved = repository.save(builder.build());
        notifyListeners(TaskEvents.CREATED, saved);
        return saved;
    }

    /** Splits a task into subtasks. All items are validated first; if any is invalid, none are saved. */
    public List<Task> breakDownTask(int id) {
        Task parent = getTask(id);
        rejectIfSubtask(parent, "id");

        List<TaskRequest> requests = callModel(() -> breakdownStrategy.breakDown(parent));
        if (requests == null || requests.isEmpty()) {
            throw BadGatewayException.invalidOutput("The breakdown produced no subtasks",
                    List.of(new FieldIssue("subtasks", "must not be empty")));
        }

        List<Task.Builder> validated = new ArrayList<>();
        List<FieldIssue> issues = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            try {
                validated.add(validator.validate(requests.get(i)));
            } catch (ValidationException e) {
                for (FieldIssue issue : e.getDetails()) {
                    issues.add(new FieldIssue("subtasks[" + i + "]." + issue.getField(), issue.getIssue()));
                }
            }
        }
        if (!issues.isEmpty()) {
            throw BadGatewayException.invalidOutput("The language model returned invalid subtasks", issues);
        }

        List<Task> saved = new ArrayList<>();
        for (Task.Builder builder : validated) {
            Task subtask = repository.save(builder.parentId(id).build());
            saved.add(subtask);
            notifyListeners(TaskEvents.CREATED, subtask);
        }
        return saved;
    }

    /** Runs a parser/strategy call. Our own ApiExceptions pass through; any other failure becomes 502. */
    private <T> T callModel(Supplier<T> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw BadGatewayException.callFailed("The language model call failed: " + e.getMessage());
        }
    }

    // ---------------- Query ----------------

    public PageResult listTasks(TaskQuery query) {
        TaskQuery q = query == null ? TaskQuery.builder().build() : query;
        List<FieldIssue> issues = new ArrayList<>();
        List<TaskFilter> filters = new ArrayList<>();

        if (q.getCompleted() != null) {
            filters.add(new CompletedFilter(q.getCompleted()));
        }
        if (q.getPriority() != null) {
            if (Priority.isValid(q.getPriority())) {
                filters.add(new PriorityFilter(Priority.valueOf(q.getPriority())));
            } else {
                issues.add(new FieldIssue("priority", "must be LOW, MEDIUM, or HIGH"));
            }
        }
        if (q.getSearch() != null && !q.getSearch().isBlank()) {
            filters.add(new SearchFilter(q.getSearch().trim()));
        }
        filters.addAll(q.getExtraFilters());

        SortStrategy sort = q.getSortBy() == null ? null : sortStrategies.find(q.getSortBy());
        if (sort == null) {
            issues.add(new FieldIssue("sortBy", "must be one of " + sortStrategies.names()));
        }
        String order = q.getOrder() == null ? "asc" : q.getOrder().toLowerCase();
        if (!order.equals("asc") && !order.equals("desc")) {
            issues.add(new FieldIssue("order", "must be \"asc\" or \"desc\""));
        }
        if (q.getPage() < 1) {
            issues.add(new FieldIssue("page", "must be 1 or greater"));
        }
        if (q.getPageSize() < 1 || q.getPageSize() > MAX_PAGE_SIZE) {
            issues.add(new FieldIssue("pageSize", "must be between 1 and " + MAX_PAGE_SIZE));
        }
        if (!issues.isEmpty()) {
            throw new ValidationException(issues);
        }

        List<Task> matching = new ArrayList<>();
        for (Task task : repository.findAll()) {
            if (matchesAll(task, filters)) {
                matching.add(task);
            }
        }
        matching.sort(sort.comparator(order.equals("asc")));

        int from = (q.getPage() - 1) * q.getPageSize();
        List<Task> pageItems = from >= matching.size()
                ? List.of()
                : matching.subList(from, Math.min(from + q.getPageSize(), matching.size()));
        return new PageResult(pageItems, q.getPage(), q.getPageSize(), matching.size());
    }

    private boolean matchesAll(Task task, List<TaskFilter> filters) {
        for (TaskFilter filter : filters) {
            if (!filter.matches(task)) {
                return false;
            }
        }
        return true;
    }

    public List<Task> listByPriority(String level) {
        Priority priority = validator.validatePriority("level", level);
        List<Task> result = new ArrayList<>();
        for (Task task : repository.findAll()) {
            if (task.getPriority() == priority) {
                result.add(task);
            }
        }
        return result;
    }
}
