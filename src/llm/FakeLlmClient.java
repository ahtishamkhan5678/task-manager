package llm;

/** Test double: always returns the same fixed reply and counts how often it was called. */
public class FakeLlmClient implements LlmClient {
    private final String reply;
    private int callCount;
    private String lastPrompt;

    public FakeLlmClient(String reply) {
        this.reply = reply;
    }

    @Override
    public synchronized String complete(String prompt) {
        callCount++;
        lastPrompt = prompt;
        return reply;
    }

    public synchronized int getCallCount() {
        return callCount;
    }

    public synchronized String getLastPrompt() {
        return lastPrompt;
    }
}
