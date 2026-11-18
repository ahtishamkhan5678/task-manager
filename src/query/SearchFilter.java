package query;

import model.Task;

/** Case-insensitive text match on title or description. */
public class SearchFilter implements TaskFilter {
    private final String needle;

    public SearchFilter(String text) {
        this.needle = text.toLowerCase();
    }

    @Override
    public boolean matches(Task task) {
        return task.getTitle().toLowerCase().contains(needle)
                || task.getDescription().toLowerCase().contains(needle);
    }
}
