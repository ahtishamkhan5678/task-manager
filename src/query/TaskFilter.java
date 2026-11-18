package query;

import model.Task;

/** One filter rule. New filters are new classes; nothing existing has to change (Open/Closed). */
public interface TaskFilter {
    boolean matches(Task task);
}
