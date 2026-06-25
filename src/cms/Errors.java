package cms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Errors {

    private Errors() {}

    private static Map<String, Object> build(int status, String error, String message, List<String> details, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("error", error);
        m.put("message", message);
        m.put("details", details != null ? details : List.of());
        m.put("path", path);
        return m;
    }

    public static Map<String, Object> validation(List<String> details, String path) {
        return build(400, "VALIDATION_ERROR", "Invalid request data.", details, path);
    }

    public static Map<String, Object> invalidJson(String path) {
        return build(400, "INVALID_JSON", "Request body is not valid JSON.", List.of(), path);
    }

    public static Map<String, Object> invalidId(String path) {
        return build(400, "INVALID_ID", "ID must be a valid UUID.", List.of(), path);
    }

    public static Map<String, Object> unauthorized(String path) {
        return build(401, "UNAUTHORIZED", "Authentication is required, or the session is invalid or expired.", List.of(), path);
    }

    public static Map<String, Object> forbidden(String message, String path) {
        return build(403, "FORBIDDEN", message != null ? message : "You do not have permission to perform this operation.", List.of(), path);
    }

    public static Map<String, Object> notFound(String resource, String path) {
        return build(404, "NOT_FOUND", resource + " not found.", List.of(), path);
    }

    public static Map<String, Object> routeNotFound(String path) {
        return build(404, "ROUTE_NOT_FOUND", "No route matches this request.", List.of(), path);
    }

    public static Map<String, Object> methodNotAllowed(List<String> allowed, String path) {
        return build(405, "METHOD_NOT_ALLOWED",
            "Method not allowed. Allowed: " + String.join(", ", allowed) + ".",
            List.of(), path);
    }

    public static Map<String, Object> tooManyRequests(String path) {
        return build(429, "TOO_MANY_REQUESTS", "Rate limit exceeded. Try again later.", List.of(), path);
    }

    public static Map<String, Object> preconditionFailed(String path) {
        return build(412, "PRECONDITION_FAILED", "ETag does not match current resource state.", List.of(), path);
    }

    public static Map<String, Object> payloadTooLarge(String path) {
        return build(413, "PAYLOAD_TOO_LARGE", "Request body too large.", List.of(), path);
    }

    public static Map<String, Object> unsupportedMediaType(String path) {
        return build(415, "UNSUPPORTED_MEDIA_TYPE", "Request body must be application/json.", List.of(), path);
    }

    public static Map<String, Object> internal(String path) {
        return build(500, "INTERNAL_ERROR", "Internal server error.", List.of(), path);
    }
}
