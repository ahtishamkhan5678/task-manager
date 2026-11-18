package llm;

/**
 * Sends one prompt to a language model and returns its text reply.
 * Being an interface lets tests plug in FakeLlmClient / FaultyLlmClient instead of the network.
 */
public interface LlmClient {
    String complete(String prompt);
}
