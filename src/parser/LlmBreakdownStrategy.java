package parser;

import exception.BadGatewayException;
import exception.FieldIssue;
import llm.LlmClient;
import model.Task;
import model.TaskRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Asks the model for subtasks, one per line as "title|description", and parses them with String methods. */
public class LlmBreakdownStrategy implements BreakdownStrategy {

    static final String PROMPT_TEMPLATE = """
            You split a task from a to-do app into smaller, concrete subtasks.

            Task title: %s
            Task description: %s

            Reply with ONLY the subtasks, one per line, in exactly this format:
            title|description

            Rules:
            - Between 2 and 6 lines.
            - title: short imperative phrase, at most 8 words. Never empty.
            - description: one sentence. Never empty.
            - Do not number the lines, do not add bullets, headings, blank lines or any other text.
            - Use the | character only once per line, as the separator.
            """;

    static final int MAX_SUBTASKS = 10;

    private final LlmClient client;

    public LlmBreakdownStrategy(LlmClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static String buildPrompt(Task parent) {
        return String.format(PROMPT_TEMPLATE, parent.getTitle(), parent.getDescription());
    }

    @Override
    public List<TaskRequest> breakDown(Task parent) {
        String reply = client.complete(buildPrompt(parent));
        if (reply == null || reply.isBlank()) {
            throw BadGatewayException.invalidOutput("The language model returned no subtasks",
                    List.of(new FieldIssue("reply", "must not be empty")));
        }

        List<TaskRequest> requests = new ArrayList<>();
        List<FieldIssue> issues = new ArrayList<>();
        String[] lines = reply.split("\\R");
        int lineNumber = 0;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            lineNumber++;
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = line.substring(2).trim();
            }
            int separator = line.indexOf('|');
            if (separator < 0 || separator != line.lastIndexOf('|')) {
                issues.add(new FieldIssue("line " + lineNumber, "is not in title|description format: \"" + line + "\""));
                continue;
            }
            String title = line.substring(0, separator).trim();
            String description = line.substring(separator + 1).trim();
            requests.add(BreakdownStrategy.subtaskRequest(parent, title, description));
        }

        if (requests.size() > MAX_SUBTASKS) {
            issues.add(new FieldIssue("reply", "has more than " + MAX_SUBTASKS + " subtasks"));
        }
        if (!issues.isEmpty()) {
            throw BadGatewayException.invalidOutput("The language model reply was not in the expected format", issues);
        }
        return requests;
    }
}
