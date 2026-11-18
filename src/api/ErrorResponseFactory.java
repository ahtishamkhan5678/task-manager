package api;

import exception.ApiException;
import exception.FieldIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory: the ONLY place the error shape is built, so every error looks the same:
 * { "error": { "code": "...", "message": "...", "details": [ { "field": "...", "issue": "..." } ] } }
 */
public final class ErrorResponseFactory {
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorResponseFactory() {
    }

    public static ApiResponse from(ApiException e) {
        return build(e.getStatus(), e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    /** Any unexpected exception becomes a 500 in the same shape. Internal details are not leaked. */
    public static ApiResponse internalError(Exception e) {
        return build(500, INTERNAL_ERROR, "An unexpected error occurred", List.of());
    }

    private static ApiResponse build(int status, String code, String message, List<FieldIssue> details) {
        List<Map<String, Object>> detailList = new ArrayList<>();
        for (FieldIssue issue : details) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("field", String.valueOf(issue.getField()));
            d.put("issue", String.valueOf(issue.getIssue()));
            detailList.add(d);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", String.valueOf(code));
        error.put("message", String.valueOf(message));
        error.put("details", detailList);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return new ApiResponse(status, body);
    }
}
