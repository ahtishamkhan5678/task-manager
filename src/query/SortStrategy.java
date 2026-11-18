package query;

import model.Task;

import java.util.Comparator;

/** One sort order. New sorts are new classes registered in SortStrategyRegistry (Open/Closed). */
public interface SortStrategy {

    /** The value clients pass as sortBy, e.g. "createdAt". */
    String name();

    Comparator<Task> comparator(boolean ascending);
}
