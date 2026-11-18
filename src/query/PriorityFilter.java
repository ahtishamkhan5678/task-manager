package query;

import model.Priority;
import model.Task;

/** Keeps only tasks with one priority. */
public class PriorityFilter implements TaskFilter {
    private final Priority priority;

    public PriorityFilter(Priority priority) {
        this.priority = priority;
    }

    @Override
    public boolean matches(Task task) {
        return task.getPriority() == priority;
    }
}
