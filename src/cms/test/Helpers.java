package cms.test;

import cms.Access;
import cms.Json;
import cms.models.FieldSpec;
import cms.models.BlogPosting;
import cms.models.Person;
import cms.models.Organization;
import cms.models.WebPage;
import cms.models.ImageObject;
import cms.models.VideoObject;
import cms.models.AudioObject;
import cms.models.CategoryCode;
import cms.models.CategoryCodeSet;
import cms.models.DefinedTerm;
import cms.models.DefinedTermSet;
import cms.models.Comment;
import cms.models.WebSite;
import cms.models.SiteNavigationElement;

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
    // Auth is mandatory on writes. The entity suite drives the API as an admin (who
    // may do everything), so the CRUD contract is exercised unchanged. The active
    // bearer token is static so the request helpers can attach it without threading
    // it through every call; caller-supplied Authorization headers win.
    private static String authToken = null;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private Helpers() {}

    public static void setBase(String url) {
        baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String getBase() { return baseUrl; }

    public static void setAuthToken(String token) { authToken = token; }

    // Logs in against an explicit server and returns the issued session token.
    public static String login(String base, String username, String password) {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("username", username);
        creds.put("password", password);
        Response r = requestTo(base, null, "POST", "/auth/login", creds);
        if (r.status != 200) {
            throw new RuntimeException("login(" + username + ") failed with " + r.status + ": " + r.raw);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.body;
        return (String) body.get("token");
    }

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
            case "Organization": return "organizations";
            case "WebPage": return "web-pages";
            case "ImageObject": return "image-objects";
            case "VideoObject": return "video-objects";
            case "AudioObject": return "audio-objects";
            case "CategoryCode": return "category-codes";
            case "CategoryCodeSet": return "category-code-sets";
            case "DefinedTerm": return "defined-terms";
            case "DefinedTermSet": return "defined-term-sets";
            case "Comment": return "comments";
            case "WebSite": return "web-sites";
            case "SiteNavigationElement": return "site-navigation-elements";
            default: throw new RuntimeException("Unknown entity: " + entity);
        }
    }

    public static Map<String, FieldSpec> fieldsOf(String entity) {
        switch (entity) {
            case "BlogPosting": return BlogPosting.FIELDS;
            case "Person": return Person.FIELDS;
            case "Organization": return Organization.FIELDS;
            case "WebPage": return WebPage.FIELDS;
            case "ImageObject": return ImageObject.FIELDS;
            case "VideoObject": return VideoObject.FIELDS;
            case "AudioObject": return AudioObject.FIELDS;
            case "CategoryCode": return CategoryCode.FIELDS;
            case "CategoryCodeSet": return CategoryCodeSet.FIELDS;
            case "DefinedTerm": return DefinedTerm.FIELDS;
            case "DefinedTermSet": return DefinedTermSet.FIELDS;
            case "Comment": return Comment.FIELDS;
            case "WebSite": return WebSite.FIELDS;
            case "SiteNavigationElement": return SiteNavigationElement.FIELDS;
            default: throw new RuntimeException("Unknown entity: " + entity);
        }
    }

    public static Set<String> requiredOf(String entity) {
        switch (entity) {
            case "BlogPosting": return BlogPosting.REQUIRED_FIELDS;
            case "Person": return Person.REQUIRED_FIELDS;
            case "Organization": return Organization.REQUIRED_FIELDS;
            case "WebPage": return WebPage.REQUIRED_FIELDS;
            case "ImageObject": return ImageObject.REQUIRED_FIELDS;
            case "VideoObject": return VideoObject.REQUIRED_FIELDS;
            case "AudioObject": return AudioObject.REQUIRED_FIELDS;
            case "CategoryCode": return CategoryCode.REQUIRED_FIELDS;
            case "CategoryCodeSet": return CategoryCodeSet.REQUIRED_FIELDS;
            case "DefinedTerm": return DefinedTerm.REQUIRED_FIELDS;
            case "DefinedTermSet": return DefinedTermSet.REQUIRED_FIELDS;
            case "Comment": return Comment.REQUIRED_FIELDS;
            case "WebSite": return WebSite.REQUIRED_FIELDS;
            case "SiteNavigationElement": return SiteNavigationElement.REQUIRED_FIELDS;
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
            if (authToken != null && !headers.containsKey("Authorization")) {
                b.header("Authorization", "Bearer " + authToken);
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
            if (authToken != null && !headers.containsKey("Authorization")) {
                b.header("Authorization", "Bearer " + authToken);
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

    // Stateless request against an explicit server with an explicit bearer (or none).
    // The auth conformance suite drives several roles and several servers, so it does
    // not rely on the static base or token. A null body sends no body; a Map (even an
    // empty one) is serialized as a JSON request body.
    public static Response requestTo(String base, String bearer, String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
            if (bearer != null) b.header("Authorization", "Bearer " + bearer);
            HttpRequest.BodyPublisher publisher;
            if (body == null) {
                publisher = HttpRequest.BodyPublishers.noBody();
            } else {
                publisher = HttpRequest.BodyPublishers.ofString(Json.stringify(body));
                b.header("Content-Type", "application/json");
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

    private static Object sampleOne(FieldSpec spec) {
        return switch (spec) {
            case FieldSpec.Scalar s -> switch (s.type()) {
                case "Text" -> "sample text";
                case "Integer" -> 42L;
                case "Number" -> 3.14;
                case "Boolean" -> true;
                case "Date", "DateTime", "Time" -> "2026-05-19T12:00:00Z";
                case "URL" -> "https://example.com/resource";
                default -> "sample";
            };
            case FieldSpec.Enumerated e -> e.values().get(0);
            case FieldSpec.Embed em -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("@type", em.type());
                m.put("alternateName", "en");
                yield m;
            }
            case FieldSpec.Ref ignored -> null;
        };
    }

    public static Map<String, Object> buildPayload(String entity, boolean partial) {
        Map<String, FieldSpec> fields = fieldsOf(entity);
        Set<String> required = requiredOf(entity);
        // System and internal fields are never sent — they are not client writable
        // and would be rejected with 400, even when a schema property shares the name.
        Set<String> readonly = Access.readonlyFields();
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, FieldSpec> e : fields.entrySet()) {
            if (readonly.contains(e.getKey())) continue;
            if (!partial && !required.contains(e.getKey())) continue;
            FieldSpec spec = e.getValue();
            Object value = spec instanceof FieldSpec.Ref ref
                ? makeDep(ref.targets().get(0))
                : sampleOne(spec);
            payload.put(e.getKey(), spec.cardinality() == FieldSpec.Cardinality.MANY ? List.of(value) : value);
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
