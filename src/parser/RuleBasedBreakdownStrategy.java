package parser;

import model.Task;
import model.TaskRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline breakdown: splits the description on commas, semicolons and the word "and".
 * If that gives fewer than two parts, falls back to Plan / Do / Review steps.
 */
public class RuleBasedBreakdownStrategy implements BreakdownStrategy {

    @Override
    public List<TaskRequest> breakDown(Task parent) {
        List<String> parts = split(parent.getDescription());
        if (parts.size() < 2) {
            parts = split(parent.getTitle());
        }
        if (parts.size() < 2) {
            parts = List.of("Plan " + parent.getTitle(), "Do " + parent.getTitle(), "Review " + parent.getTitle());
        }

        List<TaskRequest> requests = new ArrayList<>();
        for (String part : parts) {
            String title = Character.toUpperCase(part.charAt(0)) + part.substring(1);
            requests.add(BreakdownStrategy.subtaskRequest(parent, title, "Part of: " + parent.getTitle()));
        }
        return requests;
    }

    private List<String> split(String text) {
        String normalized = text.replace(";", ",").replace(" and ", ",").replace(" AND ", ",").replace(" And ", ",");
        List<String> parts = new ArrayList<>();
        for (String piece : normalized.split(",")) {
            String trimmed = piece.trim();
            while (trimmed.endsWith(".")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
