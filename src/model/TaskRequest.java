package model;

/**
 * Raw client input. Every value is a String (or null) on purpose:
 * the service's validation decides what is acceptable, not the type system.
 */
public class TaskRequest {
    private final String title;
    private final String description;
    private final String completed;
    private final String dueDate;
    private final String priority;
    private final String recurrence;

    public TaskRequest(String title, String description, String completed, String dueDate, String priority) {
        this(title, description, completed, dueDate, priority, null);
    }

    public TaskRequest(String title, String description, String completed, String dueDate,
                       String priority, String recurrence) {
        this.title = title;
        this.description = description;
        this.completed = completed;
        this.dueDate = dueDate;
        this.priority = priority;
        this.recurrence = recurrence;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCompleted() {
        return completed;
    }

    public String getDueDate() {
        return dueDate;
    }

    public String getPriority() {
        return priority;
    }

    public String getRecurrence() {
        return recurrence;
    }

    /**
     * Fingerprint used by the idempotency store: all field values joined into one String.
     * A separator character that never appears in normal text avoids "ab"+"c" == "a"+"bc" collisions.
     */
    public String fingerprint() {
        return String.join("\u001F",
                String.valueOf(title), String.valueOf(description), String.valueOf(completed),
                String.valueOf(dueDate), String.valueOf(priority), String.valueOf(recurrence));
    }

    @Override
    public String toString() {
        return "TaskRequest{title='" + title + "', description='" + description + "', completed=" + completed
                + ", dueDate=" + dueDate + ", priority=" + priority + ", recurrence=" + recurrence + '}';
    }
}
