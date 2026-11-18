package exception;

import java.util.List;

/** 400 - the client sent invalid input. Collects every field issue so one request reports all problems. */
public class ValidationException extends ApiException {
    public static final String CODE = "VALIDATION_ERROR";

    public ValidationException(List<FieldIssue> issues) {
        super(400, CODE, "Validation failed", issues);
    }

    public ValidationException(String field, String issue) {
        this(List.of(new FieldIssue(field, issue)));
    }
}
