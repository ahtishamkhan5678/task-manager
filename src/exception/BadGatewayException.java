package exception;

import java.util.List;

/** 502 - the language model failed, timed out, or returned output that did not pass validation. */
public class BadGatewayException extends ApiException {
    /** The model call itself failed (network error, timeout, provider error). */
    public static final String LLM_CALL_FAILED = "LLM_CALL_FAILED";
    /** The model answered, but its answer was unusable (bad JSON, empty title, impossible date...). */
    public static final String LLM_INVALID_OUTPUT = "LLM_INVALID_OUTPUT";

    public BadGatewayException(String errorCode, String message, List<FieldIssue> details) {
        super(502, errorCode, message, details);
    }

    public static BadGatewayException callFailed(String message) {
        return new BadGatewayException(LLM_CALL_FAILED, message, List.of());
    }

    public static BadGatewayException invalidOutput(String message, List<FieldIssue> details) {
        return new BadGatewayException(LLM_INVALID_OUTPUT, message, details);
    }
}
