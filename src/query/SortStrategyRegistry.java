package query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Looks up a SortStrategy by its name. Register new strategies instead of editing a switch statement. */
public class SortStrategyRegistry {
    private final Map<String, SortStrategy> strategies = new LinkedHashMap<>();

    /** Registry with the two built-in sorts: createdAt and dueDate. */
    public static SortStrategyRegistry withDefaults() {
        SortStrategyRegistry registry = new SortStrategyRegistry();
        registry.register(new CreatedAtSort());
        registry.register(new DueDateSort());
        return registry;
    }

    public void register(SortStrategy strategy) {
        strategies.put(strategy.name(), strategy);
    }

    public SortStrategy find(String name) {
        return strategies.get(name);
    }

    public Set<String> names() {
        return strategies.keySet();
    }
}
