package exception;

import java.util.Collections;
import java.util.List;

/**
 * Base of the exception hierarchy. Every subclass knows its HTTP-style status code
 * and its machine-readable error code, so ErrorResponseFactory can build the error shape.
 */
public abstract class ApiException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final List<FieldIssue> details;

    protected ApiException(int status, String errorCode, String message) {
        this(status, errorCode, message, Collections.emptyList());
    }

    protected ApiException(int status, String errorCode, String message, List<FieldIssue> details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details == null ? Collections.emptyList() : List.copyOf(details);
    }

    public int getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<FieldIssue> getDetails() {
        return details;
    }
}
