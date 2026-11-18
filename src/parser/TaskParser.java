package parser;

import model.ParsedTask;

/**
 * Strategy interface: turns plain English into a ParsedTask.
 *
 * @param text  the user's sentence, e.g. "Remind me to pay rent on the 1st of every month"
 * @param today today's date as YYYY-MM-DD, so relative dates ("tomorrow", "next Friday") can be resolved
 *
 * Implementations may throw any exception on failure; TaskService turns failures into 502
 * (or 503 for ServiceUnavailableException) and validates the result before saving.
 */
public interface TaskParser {
    ParsedTask parse(String text, String today);
}
