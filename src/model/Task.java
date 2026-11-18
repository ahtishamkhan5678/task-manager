package model;

/**
 * Immutable task. All fields are private final, there are no setters,
 * and the only way to create one is through {@link Builder}.
 * To "change" a task, copy it with {@link #toBuilder()} and build a new one.
 */
public final class Task {
    private final int id;
    private final String title;
    private final String description;
    private final boolean completed;
    private final String dueDate;
    private final Priority priority;
    private final Integer parentId;
    private final long createdAt;
    private final Recurrence recurrence;

    private Task(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.description = builder.description;
        this.completed = builder.completed;
        this.dueDate = builder.dueDate;
        this.priority = builder.priority;
        this.parentId = builder.parentId;
        this.createdAt = builder.createdAt;
        this.recurrence = builder.recurrence;
    }

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isCompleted() {
        return completed;
    }

    public String getDueDate() {
        return dueDate;
    }

    public Priority getPriority() {
        return priority;
    }

    public Integer getParentId() {
        return parentId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    /** A task with a parent is a subtask. */
    public boolean isSubtask() {
        return parentId != null;
    }

    /** Copy of this task as a builder, used to create modified versions. */
    public Builder toBuilder() {
        return new Builder(title, description, completed)
                .id(id)
                .dueDate(dueDate)
                .priority(priority)
                .parentId(parentId)
                .createdAt(createdAt)
                .recurrence(recurrence);
    }

    @Override
    public String toString() {
        return "Task{id=" + id
                + ", title='" + title + '\''
                + ", completed=" + completed
                + ", dueDate=" + dueDate
                + ", priority=" + priority
                + ", parentId=" + parentId
                + (recurrence != null ? ", recurrence=" + recurrence : "")
                + '}';
    }

    /** Builder: title, description and completed are required (constructor); the rest are optional. */
    public static final class Builder {
        private int id;
        private String title;
        private String description;
        private boolean completed;
        private String dueDate;
        private Priority priority = Priority.MEDIUM;
        private Integer parentId;
        private long createdAt;
        private Recurrence recurrence;

        public Builder(String title, String description, boolean completed) {
            this.title = title;
            this.description = description;
            this.completed = completed;
        }

        public Builder id(int id) {
            this.id = id;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder dueDate(String dueDate) {
            this.dueDate = dueDate;
            return this;
        }

        public Builder priority(Priority priority) {
            this.priority = priority;
            return this;
        }

        public Builder parentId(Integer parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder recurrence(Recurrence recurrence) {
            this.recurrence = recurrence;
            return this;
        }

        public Task build() {
            if (title == null || description == null) {
                throw new IllegalStateException("title and description are required");
            }
            if (priority == null) {
                priority = Priority.MEDIUM;
            }
            if (createdAt == 0) {
                createdAt = System.currentTimeMillis();
            }
            return new Task(this);
        }
    }
}
