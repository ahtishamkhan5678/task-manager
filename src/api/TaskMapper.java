package api;

import model.Task;
import query.PageResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns model objects into JSON-style Maps for response bodies. */
final class TaskMapper {

    private TaskMapper() {
    }

    static Map<String, Object> toMap(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId());
        m.put("title", task.getTitle());
        m.put("description", task.getDescription());
        m.put("completed", task.isCompleted());
        m.put("dueDate", task.getDueDate());
        m.put("priority", task.getPriority().name());
        m.put("parentId", task.getParentId());
        m.put("recurrence", task.getRecurrence() == null ? null : task.getRecurrence().name());
        m.put("createdAt", task.getCreatedAt());
        return m;
    }

    static List<Map<String, Object>> toList(List<Task> tasks) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Task t : tasks) {
            list.add(toMap(t));
        }
        return list;
    }

    static Map<String, Object> toPage(PageResult page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", toList(page.getItems()));
        m.put("page", page.getPage());
        m.put("pageSize", page.getPageSize());
        m.put("totalItems", page.getTotalItems());
        m.put("totalPages", page.getTotalPages());
        return m;
    }
}
