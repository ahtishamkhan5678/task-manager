package llm;

/** The network call to the model failed (timeout, connection error, non-2xx status). */
public class LlmCallException extends RuntimeException {
    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
