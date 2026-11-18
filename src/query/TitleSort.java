package query;

import model.Task;

import java.util.Comparator;

/** Extra sort registered at runtime to show Open/Closed: no existing class was edited to add it. */
public class TitleSort implements SortStrategy {
    @Override
    public String name() {
        return "title";
    }

    @Override
    public Comparator<Task> comparator(boolean ascending) {
        Comparator<Task> c = Comparator.comparing((Task t) -> t.getTitle().toLowerCase()).thenComparingInt(Task::getId);
        return ascending ? c : c.reversed();
    }
}
