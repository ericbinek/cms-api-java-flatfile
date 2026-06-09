package cms.test;

import cms.Json;
import cms.models.BlogPosting;
import cms.models.Person;
import cms.models.WebPage;
import cms.models.ImageObject;
import cms.models.CategoryCode;
import cms.models.CategoryCodeSet;
import cms.models.DefinedTerm;
import cms.models.DefinedTermSet;
import cms.models.Comment;
import cms.models.WebSite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Helpers {

    private static String baseUrl = "";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private Helpers() {}

    public static void setBase(String url) {
        baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String getBase() { return baseUrl; }

    public static boolean waitForHealth(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Response r = request("GET", "/health", null, Map.of());
                if (r.status == 200) return true;
            } catch (Exception ignored) {
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    public static String pluralOf(String entity) {
        switch (entity) {
            case "BlogPosting": return "blog-postings";
            case "Person": return "persons";
            case "WebPage": return "web-pages";
            case "ImageObject": return "image-objects";
            case "CategoryCode": return "category-codes";
            case "CategoryCodeSet": return "category-code-sets";
            case "DefinedTerm": return "defined-terms";
            case "DefinedTermSet": return "defined-term-sets";
            case "Comment": return "comments";
            case "WebSite": return "web-sites";
            default: throw new RuntimeException("Unknown entity: " + entity);
        }
    }

    public static Map<String, Map<String, Object>> fieldsOf(String entity) {
        switch (entity) {
            case "BlogPosting": return BlogPosting.FIELDS;
            case "Person": return Person.FIELDS;
            case "WebPage": return WebPage.FIELDS;
            case "ImageObject": return ImageObject.FIELDS;
            case "CategoryCode": return CategoryCode.FIELDS;
            case "CategoryCodeSet": return CategoryCodeSet.FIELDS;
            case "DefinedTerm": return DefinedTerm.FIELDS;
            case "DefinedTermSet": return DefinedTermSet.FIELDS;
            case "Comment": return Comment.FIELDS;
            case "WebSite": return WebSite.FIELDS;
            default: throw new RuntimeException("Unknown entity: " + entity);
        }
    }

    public static Set<String> requiredOf(String entity) {
        switch (entity) {
            case "BlogPosting": return BlogPosting.REQUIRED_FIELDS;
            case "Person": return Person.REQUIRED_FIELDS;
            case "WebPage": return WebPage.REQUIRED_FIELDS;
            case "ImageObject": return ImageObject.REQUIRED_FIELDS;
            case "CategoryCode": return CategoryCode.REQUIRED_FIELDS;
            case "CategoryCodeSet": return CategoryCodeSet.REQUIRED_FIELDS;
            case "DefinedTerm": return DefinedTerm.REQUIRED_FIELDS;
            case "DefinedTermSet": return DefinedTermSet.REQUIRED_FIELDS;
            case "Comment": return Comment.REQUIRED_FIELDS;
            case "WebSite": return WebSite.REQUIRED_FIELDS;
            default: throw new RuntimeException("Unknown entity: " + entity);
        }
    }

    public static class Response {
        public final int status;
        public final Map<String, String> headers;
        public final Object body;
        public final String raw;

        public Response(int status, Map<String, String> headers, String raw) {
            this.status = status;
            this.headers = headers;
            this.raw = raw;
            this.body = raw == null || raw.isEmpty() ? null : Json.parse(raw);
        }
    }

    public static Response request(String method, String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
            HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.stringify(body));
            if (body != null) b.header("Content-Type", "application/json");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                b.header(e.getKey(), e.getValue());
            }
            b.method(method, publisher);
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> hdrs = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> { if (!v.isEmpty()) hdrs.put(k.toLowerCase(), v.get(0)); });
            return new Response(r.statusCode(), hdrs, r.body());
        } catch (Exception e) {
            throw new RuntimeException("Request failed: " + e.getMessage(), e);
        }
    }

    // Sends the body verbatim (no serialization) — for exercising the parser with
    // raw payloads such as deeply nested JSON.
    public static Response requestRaw(String method, String path, String rawBody, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                b.header(e.getKey(), e.getValue());
            }
            b.method(method, HttpRequest.BodyPublishers.ofString(rawBody));
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> hdrs = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> { if (!v.isEmpty()) hdrs.put(k.toLowerCase(), v.get(0)); });
            return new Response(r.statusCode(), hdrs, r.body());
        } catch (Exception e) {
            throw new RuntimeException("Request failed: " + e.getMessage(), e);
        }
    }

    private static Object sampleOne(Map<String, Object> spec) {
        String kind = (String) spec.get("kind");
        if ("scalar".equals(kind)) {
            switch ((String) spec.get("type")) {
                case "Text": return "sample text";
                case "Integer": return 42L;
                case "Number": return 3.14;
                case "Boolean": return true;
                case "Date":
                case "DateTime":
                case "Time": return "2026-05-19T12:00:00Z";
                case "URL": return "https://example.com/resource";
                default: return "sample";
            }
        }
        if ("enum".equals(kind)) {
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) spec.get("values");
            return values.get(0);
        }
        if ("embed".equals(kind)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("@type", spec.get("type"));
            m.put("alternateName", "en");
            return m;
        }
        return null;
    }

    public static Map<String, Object> buildPayload(String entity, boolean partial) {
        Map<String, Map<String, Object>> fields = fieldsOf(entity);
        Set<String> required = requiredOf(entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : fields.entrySet()) {
            if (!partial && !required.contains(e.getKey())) continue;
            Map<String, Object> spec = e.getValue();
            Object value;
            if ("ref".equals(spec.get("kind"))) {
                @SuppressWarnings("unchecked")
                List<String> targets = (List<String>) spec.get("targets");
                value = makeDep(targets.get(0));
            } else {
                value = sampleOne(spec);
            }
            payload.put(e.getKey(), "many".equals(spec.get("cardinality")) ? List.of(value) : value);
        }
        return payload;
    }

    public static String makeDep(String entity) {
        Map<String, Object> payload = buildPayload(entity, false);
        Response r = request("POST", "/" + pluralOf(entity), payload, Map.of());
        if (r.status != 201) {
            throw new RuntimeException("makeDep(" + entity + ") failed: " + r.status + " " + r.raw);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.body;
        return (String) body.get("id");
    }
}
