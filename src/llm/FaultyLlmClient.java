package llm;

/** Test double that misbehaves in a chosen way, so we can prove bad model output never gets saved. */
public class FaultyLlmClient implements LlmClient {

    public enum Fault {
        EMPTY_TITLE,
        IMPOSSIBLE_DATE,
        WRONG_TYPE,
        MALFORMED_TEXT,
        EMPTY_REPLY,
        BAD_BREAKDOWN_LINE,
        THROWS,
        TIMEOUT
    }

    private final Fault fault;
    private int callCount;

    public FaultyLlmClient(Fault fault) {
        this.fault = fault;
    }

    @Override
    public synchronized String complete(String prompt) {
        callCount++;
        return switch (fault) {
            case EMPTY_TITLE -> "{\"title\": \"\", \"description\": \"Pay the rent\", \"dueDate\": \"2026-12-01\", \"recurrence\": null}";
            case IMPOSSIBLE_DATE -> "{\"title\": \"Pay rent\", \"description\": \"Pay the rent\", \"dueDate\": \"2026-02-30\", \"recurrence\": null}";
            case WRONG_TYPE -> "{\"title\": 42, \"description\": \"Pay the rent\", \"dueDate\": \"2026-12-01\", \"recurrence\": null}";
            case MALFORMED_TEXT -> "Sure! Here is your task: pay rent on the first.";
            case EMPTY_REPLY -> "";
            case BAD_BREAKDOWN_LINE -> "Book venue|Call three venues and compare prices\nthis line has no separator\n|Missing title";
            case THROWS -> throw new LlmCallException("connection refused");
            case TIMEOUT -> throw new LlmCallException("LLM call timed out");
        };
    }

    public synchronized int getCallCount() {
        return callCount;
    }
}
