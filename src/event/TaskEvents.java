package event;

/** Event type names sent to listeners. */
public final class TaskEvents {
    public static final String CREATED = "TASK_CREATED";
    public static final String UPDATED = "TASK_UPDATED";
    public static final String COMPLETED = "TASK_COMPLETED";
    public static final String DELETED = "TASK_DELETED";

    private TaskEvents() {
    }
}
