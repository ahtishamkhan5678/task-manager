package event;

import model.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps a count of each event type and a simple history line per event. */
public class AuditTrailListener implements TaskEventListener {
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();

    @Override
    public synchronized void onEvent(String eventType, Task task) {
        counts.put(eventType, counts.getOrDefault(eventType, 0) + 1);
        history.add(eventType + " #" + task.getId());
    }

    public synchronized int getCount(String eventType) {
        return counts.getOrDefault(eventType, 0);
    }

    public synchronized Map<String, Integer> getCounts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    public synchronized List<String> getHistory() {
        return List.copyOf(history);
    }
}
