package exception;

/** 422 - an idempotency key was reused with a different request. */
public class ConflictException extends ApiException {
    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    public ConflictException(String message) {
        super(422, CODE, message);
    }
}
