package parser;

import llm.HttpLlmClient;
import llm.LlmClient;
import llm.MissingKeyLlmClient;

/**
 * Factory for the parsing strategies. TaskService only sees the TaskParser / BreakdownStrategy
 * interfaces and never knows which implementation it got (Strategy + Factory).
 *
 * Modes:
 *   LLM        - always the model. If LLM_API_KEY is missing, calls return 503.
 *   RULE_BASED - always the offline String-method version.
 *   AUTO       - the model when a key is present, otherwise rule-based.
 */
public final class ParserFactory {
    public static final String API_KEY_ENV = "LLM_API_KEY";

    public enum Mode { LLM, RULE_BASED, AUTO }

    private ParserFactory() {
    }

    // ---------- task parsers ----------

    /** Uses System.getenv("LLM_API_KEY"). */
    public static TaskParser createTaskParser(Mode mode) {
        return createTaskParser(mode, System.getenv(API_KEY_ENV));
    }

    public static TaskParser createTaskParser(Mode mode, String apiKey) {
        return switch (resolve(mode, apiKey)) {
            case RULE_BASED -> new RuleBasedTaskParser();
            default -> new LlmTaskParser(createLlmClient(apiKey));
        };
    }

    /** Plug in any LlmClient, e.g. FakeLlmClient or FaultyLlmClient in tests. */
    public static TaskParser createTaskParser(LlmClient client) {
        return new LlmTaskParser(client);
    }

    // ---------- breakdown strategies ----------

    public static BreakdownStrategy createBreakdownStrategy(Mode mode) {
        return createBreakdownStrategy(mode, System.getenv(API_KEY_ENV));
    }

    public static BreakdownStrategy createBreakdownStrategy(Mode mode, String apiKey) {
        return switch (resolve(mode, apiKey)) {
            case RULE_BASED -> new RuleBasedBreakdownStrategy();
            default -> new LlmBreakdownStrategy(createLlmClient(apiKey));
        };
    }

    public static BreakdownStrategy createBreakdownStrategy(LlmClient client) {
        return new LlmBreakdownStrategy(client);
    }

    // ---------- helpers ----------

    public static boolean hasKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    private static Mode resolve(Mode mode, String apiKey) {
        if (mode == Mode.AUTO) {
            return hasKey(apiKey) ? Mode.LLM : Mode.RULE_BASED;
        }
        return mode;
    }

    /** Real HTTP client when a key exists; otherwise a client whose every call returns 503. */
    private static LlmClient createLlmClient(String apiKey) {
        return hasKey(apiKey) ? HttpLlmClient.fromEnvironment(apiKey) : new MissingKeyLlmClient();
    }
}
