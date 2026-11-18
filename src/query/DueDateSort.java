package query;

import model.Task;

import java.util.Comparator;

/**
 * Sort by due date. YYYY-MM-DD strings sort correctly as text.
 * Tasks with no due date always go last, in both directions.
 */
public class DueDateSort implements SortStrategy {
    @Override
    public String name() {
        return "dueDate";
    }

    @Override
    public Comparator<Task> comparator(boolean ascending) {
        Comparator<String> byDate = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        return Comparator.comparing(Task::getDueDate, Comparator.nullsLast(byDate))
                .thenComparingInt(Task::getId);
    }
}
