package exception;

/** 503 - the AI feature cannot run, e.g. LLM_API_KEY is not set. */
public class ServiceUnavailableException extends ApiException {
    public static final String CODE = "LLM_UNAVAILABLE";

    public ServiceUnavailableException(String message) {
        super(503, CODE, message);
    }
}
