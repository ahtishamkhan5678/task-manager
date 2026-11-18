package query;

import model.Task;

/**
 * Example of extending the system without editing existing classes:
 * this filter is passed in with TaskQuery.Builder.filter(...).
 */
public class HasDueDateFilter implements TaskFilter {
    @Override
    public boolean matches(Task task) {
        return task.getDueDate() != null;
    }
}
