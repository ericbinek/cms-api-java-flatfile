package cms;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public final class Http {

    public static final int MAX_BODY_SIZE = 1024 * 1024;

    public static final Map<String, String> CORS_HEADERS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Access-Control-Allow-Origin", "*");
        m.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        m.put("Access-Control-Allow-Headers", "Content-Type, If-Match, If-None-Match");
        m.put("Access-Control-Expose-Headers", "ETag");
        m.put("X-Content-Type-Options", "nosniff");
        m.put("X-Frame-Options", "DENY");
        m.put("Referrer-Policy", "no-referrer");
        m.put("Cache-Control", "no-store");
        CORS_HEADERS = java.util.Collections.unmodifiableMap(m);
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE);

    private Http() {}

    public static class BodyTooLargeException extends RuntimeException {
        public BodyTooLargeException() { super("Request body too large."); }
    }

    public static class UnsupportedMediaTypeException extends RuntimeException {
        public UnsupportedMediaTypeException() { super("Request body must be application/json."); }
    }

    public static String requestPath(HttpExchange exchange) {
        return exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
    }

    public static boolean isValidUuid(String s) {
        return s != null && UUID_PATTERN.matcher(s).matches();
    }

    public static Map<String, Object> parseBody(HttpExchange exchange) throws IOException {
        long lenHeader;
        try {
            lenHeader = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length") != null
                ? exchange.getRequestHeaders().getFirst("Content-Length") : "0");
        } catch (NumberFormatException e) {
            lenHeader = 0;
        }
        if (lenHeader > MAX_BODY_SIZE) throw new BodyTooLargeException();
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int n;
        while ((n = is.read(chunk)) > 0) {
            total += n;
            if (total > MAX_BODY_SIZE) throw new BodyTooLargeException();
            buf.write(chunk, 0, n);
        }
        byte[] all = buf.toByteArray();
        if (all.length == 0) return new LinkedHashMap<>();
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String mediaType = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (!mediaType.equals("application/json")) throw new UnsupportedMediaTypeException();
        Object parsed = Json.parse(new String(all, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) return new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    private static void applyCors(HttpExchange exchange) {
        for (Map.Entry<String, String> e : CORS_HEADERS.entrySet()) {
            exchange.getResponseHeaders().set(e.getKey(), e.getValue());
        }
    }

    public static void preflight(HttpExchange exchange) throws IOException {
        applyCors(exchange);
        exchange.sendResponseHeaders(204, -1);
    }

    public static void json(HttpExchange exchange, int status, Object data) throws IOException {
        json(exchange, status, data, null, null);
    }

    public static void json(HttpExchange exchange, int status, Object data, Map<String, String> extra) throws IOException {
        json(exchange, status, data, extra, null);
    }

    /**
     * Single-record responses pass the record's canonical ETag (the stored
     * record's version — the same value If-Match is checked against). Without
     * one the ETag falls back to a hash of the response body; lists and errors
     * have no single record version.
     */
    public static void json(HttpExchange exchange, int status, Object data, Map<String, String> extra, String etag) throws IOException {
        applyCors(exchange);
        if (status == 204) {
            if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) exchange.getResponseHeaders().set(e.getKey(), e.getValue());
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String body = Json.stringify(data);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String responseETag = etag != null ? etag : Validation.etagFor(bytes);
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null && (ifNoneMatch.equals(responseETag) || ifNoneMatch.equals("*"))) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("ETag", responseETag);
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) exchange.getResponseHeaders().set(e.getKey(), e.getValue());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void jsonError(HttpExchange exchange, Map<String, Object> error) throws IOException {
        Object status = error.get("status");
        int code = status instanceof Number ? ((Number) status).intValue() : 500;
        json(exchange, code, error);
    }
}
