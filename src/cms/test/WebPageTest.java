package cms.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WebPageTest {

    public static final String ENTITY = "WebPage";
    public static final String BASE = "/web-pages";

    private WebPageTest() {}

    public static void run(TestRunner.TestContext ctx) {
        ctx.test("create returns 201 with @type and id", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(201, r.status, "expected 201");
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal(ENTITY, body.get("@type"));
            Assert.equal("https://schema.org", body.get("@context"));
            Assert.isTrue(body.get("id") != null, "id should be set");
        });

        ctx.test("GET by id returns 200 with ETag", () -> {
            Helpers.Response c = Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) c.body;
            Helpers.Response r = Helpers.request("GET", BASE + "/" + body.get("id"), null, Map.of());
            Assert.equal(200, r.status);
            Assert.isTrue(r.headers.get("etag") != null, "ETag header should be present");
        });

        ctx.test("list returns { items, total } envelope", () -> {
            Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            Helpers.Response r = Helpers.request("GET", BASE, null, Map.of());
            Assert.equal(200, r.status);
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.isTrue(body.get("items") instanceof List, "items list expected");
            Assert.isTrue(body.get("total") instanceof Number, "total number expected");
        });

        ctx.test("PUT partial update returns 200", () -> {
            Helpers.Response c = Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) c.body;
            Map<String, Object> partial = Helpers.buildPayload(ENTITY, true);
            Helpers.Response r = Helpers.request("PUT", BASE + "/" + body.get("id"), partial, Map.of());
            Assert.equal(200, r.status, "PUT expected 200, got " + r.status + ": " + r.raw);
        });

        ctx.test("DELETE returns 204 and subsequent GET returns 404", () -> {
            Helpers.Response c = Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) c.body;
            Helpers.Response d = Helpers.request("DELETE", BASE + "/" + body.get("id"), null, Map.of());
            Assert.equal(204, d.status);
            Helpers.Response g = Helpers.request("GET", BASE + "/" + body.get("id"), null, Map.of());
            Assert.equal(404, g.status);
        });

        ctx.test("invalid UUID returns 400 INVALID_ID", () -> {
            Helpers.Response r = Helpers.request("GET", BASE + "/not-a-uuid", null, Map.of());
            Assert.equal(400, r.status);
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("INVALID_ID", body.get("error"));
        });

        ctx.test("unknown id returns 404 NOT_FOUND", () -> {
            Helpers.Response r = Helpers.request("GET", BASE + "/00000000-0000-0000-0000-000000000000", null, Map.of());
            Assert.equal(404, r.status);
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("NOT_FOUND", body.get("error"));
        });

        ctx.test("pagination — limit + offset honour total", () -> {
            Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            Helpers.Response r = Helpers.request("GET", BASE + "?limit=2&offset=0", null, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.isTrue(((Number) body.get("total")).intValue() >= 3, "total >= 3");
            @SuppressWarnings("unchecked") List<Object> items = (List<Object>) body.get("items");
            Assert.isTrue(items.size() <= 2, "items.size <= 2");
        });

        ctx.test("sort by headline accepted", () -> {
            Helpers.Response r = Helpers.request("GET", BASE + "?sort=headline&order=asc", null, Map.of());
            Assert.equal(200, r.status);
        });

        ctx.test("unknown sort field rejected with 400", () -> {
            Helpers.Response r = Helpers.request("GET", BASE + "?sort=definitely-not-a-field", null, Map.of());
            Assert.equal(400, r.status);
        });


        ctx.test("filter on text field \"headline\" returns matches", () -> {
            Helpers.Response c = Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) c.body;
            String value = (String) body.getOrDefault("headline", "");
            String needle = value.length() >= 4 ? value.substring(0, 4) : value;
            if (needle.isEmpty()) return;
            String encoded = java.net.URLEncoder.encode(needle, java.nio.charset.StandardCharsets.UTF_8);
            Helpers.Response r = Helpers.request("GET", BASE + "?headline=" + encoded, null, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> rb = (Map<String, Object>) r.body;
            @SuppressWarnings("unchecked") List<Object> items = (List<Object>) rb.get("items");
            boolean found = false;
            for (Object item : items) {
                @SuppressWarnings("unchecked") Map<String, Object> mItem = (Map<String, Object>) item;
                if (body.get("id").equals(mItem.get("id"))) { found = true; break; }
            }
            Assert.isTrue(found, "created item not found via filter");
        });

        ctx.test("stale If-Match on PUT returns 412", () -> {
            Helpers.Response c = Helpers.request("POST", BASE, Helpers.buildPayload(ENTITY, false), Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) c.body;
            Helpers.Response r = Helpers.request("PUT", BASE + "/" + body.get("id"), new LinkedHashMap<>(),
                Map.of("If-Match", "\"0000000000000000\""));
            Assert.equal(412, r.status);
        });

        ctx.test("CORS preflight returns 204 with allow headers", () -> {
            Helpers.Response r = Helpers.request("OPTIONS", BASE, null,
                Map.of("Origin", "https://example.com", "Access-Control-Request-Method", "POST"));
            Assert.equal(204, r.status);
            Assert.equal("*", r.headers.get("access-control-allow-origin"));
        });

        ctx.test("deeply nested JSON body rejected with 400 INVALID_JSON", () -> {
            int depth = 2000;
            String deep = "[".repeat(depth) + "]".repeat(depth);
            Helpers.Response r = Helpers.requestRaw("POST", BASE, deep, Map.of());
            Assert.equal(400, r.status);
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("INVALID_JSON", body.get("error"));
        });

        ctx.test("leading/trailing whitespace is trimmed on create", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            payload.put("headline", "  trimmed value  ");
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(201, r.status, "expected 201");
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("trimmed value", body.get("headline"));
        });

        ctx.test("control characters are stripped on create", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            payload.put("headline", "clean\u0000\u0007ed");
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(201, r.status, "expected 201");
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("cleaned", body.get("headline"));
        });

        ctx.test("value over maxLength rejected with 400 VALIDATION_ERROR", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            payload.put("headline", "a".repeat(257));
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(400, r.status);
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("VALIDATION_ERROR", body.get("error"));
        });

        ctx.test("value at maxLength accepted", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            payload.put("headline", "a".repeat(256));
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(201, r.status, "expected 201");
        });

        ctx.test("multiline field \"description\" keeps internal newlines", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            payload.put("description", "first line\nsecond line");
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(201, r.status, "expected 201");
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("first line\nsecond line", body.get("description"));
        });

        ctx.test("single-line field \"headline\" strips newlines", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, false);
            payload.put("headline", "first\nsecond");
            Helpers.Response r = Helpers.request("POST", BASE, payload, Map.of());
            Assert.equal(201, r.status, "expected 201");
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
            Assert.equal("firstsecond", body.get("headline"));
        });

        ctx.test("GET by id embeds \"author\" as an object; list stays flat", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, true);
            Helpers.Response c = Helpers.request("POST", BASE, payload, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) c.body;

            // POST response keeps refs flat (UUID strings).
            Object refId = created.get("author");
            Assert.isTrue(refId instanceof String, "POST response keeps ref as UUID string");

            // Single-resource GET embeds the referenced entity one level deep.
            Helpers.Response g = Helpers.request("GET", BASE + "/" + created.get("id"), null, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> got = (Map<String, Object>) g.body;
            Object embedded = got.get("author");
            Assert.isTrue(embedded instanceof Map, "GET by id embeds ref as an object");
            @SuppressWarnings("unchecked") Map<String, Object> embObj = (Map<String, Object>) embedded;
            Assert.equal("Person", embObj.get("@type"));
            Assert.equal(refId, embObj.get("id"));

            // List responses stay flat — refs remain UUID strings.
            Helpers.Response l = Helpers.request("GET", BASE + "?limit=100", null, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> lb = (Map<String, Object>) l.body;
            @SuppressWarnings("unchecked") List<Object> items = (List<Object>) lb.get("items");
            Object inListRef = null;
            for (Object o : items) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                if (created.get("id").equals(m.get("id"))) { inListRef = m.get("author"); break; }
            }
            Assert.isTrue(inListRef instanceof String, "list keeps ref as UUID string");
        });

        ctx.test("GET by id leaves an unresolvable \"author\" ref as its UUID", () -> {
            String dangling = "00000000-0000-0000-0000-000000000000";
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, true);
            payload.put("author", dangling);
            Helpers.Response c = Helpers.request("POST", BASE, payload, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) c.body;
            Helpers.Response g = Helpers.request("GET", BASE + "/" + created.get("id"), null, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> got = (Map<String, Object>) g.body;
            Assert.equal(dangling, got.get("author"));
        });

        ctx.test("fresh ETag from GET satisfies If-Match on PUT, then DELETE", () -> {
            Map<String, Object> payload = Helpers.buildPayload(ENTITY, true);
            Helpers.Response c = Helpers.request("POST", BASE, payload, Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) c.body;

            Helpers.Response got = Helpers.request("GET", BASE + "/" + created.get("id"), null, Map.of());
            Assert.equal(200, got.status);
            String etag = got.headers.get("etag");
            Assert.isTrue(etag != null, "ETag header should be present");

            // The observable ETag names the record version: a conditional GET with it is a 304.
            Helpers.Response notModified = Helpers.request("GET", BASE + "/" + created.get("id"), null,
                Map.of("If-None-Match", etag));
            Assert.equal(304, notModified.status);

            // The honest fresh path: PUT with the ETag the GET handed out succeeds.
            Helpers.Response put = Helpers.request("PUT", BASE + "/" + created.get("id"), new LinkedHashMap<>(),
                Map.of("If-Match", etag));
            Assert.equal(200, put.status, "PUT with fresh If-Match expected 200, got " + put.status + ": " + put.raw);

            // The PUT response carries the new record version; DELETE with it succeeds.
            String putEtag = put.headers.get("etag");
            Assert.isTrue(putEtag != null, "PUT response should carry an ETag");
            Helpers.Response del = Helpers.request("DELETE", BASE + "/" + created.get("id"), null,
                Map.of("If-Match", putEtag));
            Assert.equal(204, del.status);
        });
    }
}
