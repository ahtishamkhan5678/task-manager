package api;

import util.SimpleJson;

/**
 * What every TaskApi operation returns: a status code (200, 201, 400, 404, 422, 500, 502, 503)
 * and a JSON-style body made of Maps, Lists, Strings, numbers and booleans.
 * Stands in for the instructor-provided ApiResponse.
 */
public final class ApiResponse {
    private final int status;
    private final Object body;

    public ApiResponse(int status, Object body) {
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public Object getBody() {
        return body;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public String toJson() {
        return SimpleJson.writePretty(body);
    }

    @Override
    public String toString() {
        return "HTTP " + status + "\n" + toJson();
    }
}
