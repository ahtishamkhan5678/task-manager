package query;

import model.Task;

import java.util.Comparator;

/** Sort by creation time; ties broken by id so the order is stable. */
public class CreatedAtSort implements SortStrategy {
    @Override
    public String name() {
        return "createdAt";
    }

    @Override
    public Comparator<Task> comparator(boolean ascending) {
        Comparator<Task> c = Comparator.comparingLong(Task::getCreatedAt).thenComparingInt(Task::getId);
        return ascending ? c : c.reversed();
    }
}
