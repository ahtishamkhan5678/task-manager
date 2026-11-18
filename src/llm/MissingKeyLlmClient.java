package llm;

import exception.ServiceUnavailableException;

/** Used when LLM_API_KEY is not set: every call becomes a 503 instead of a crash. */
public class MissingKeyLlmClient implements LlmClient {
    @Override
    public String complete(String prompt) {
        throw new ServiceUnavailableException(
                "AI features are unavailable: the LLM_API_KEY environment variable is not set");
    }
}
