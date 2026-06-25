package cms.test;

import cms.test.TestRunner.TestContext;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RateLimitTest {

    private static final String BASE = "/blog-postings";

    private RateLimitTest() {}

    // Reads and writes have independent per-IP windows. Each test starts its own
    // server with one bucket set low and the other effectively unlimited, then
    // drives requests until the limiter trips. Exact counts are not asserted —
    // startup spends a request or two (health probe) — only that limiting
    // eventually engages after at least one request is admitted, and that the
    // rejection carries the 429 envelope and a sane Retry-After header. Requests
    // go out unauthenticated: the limiter runs before auth, so they still count.
    public static void run(TestContext ctx) {
        ctx.test("writes over the limit are rejected with 429 and Retry-After", () -> {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("RATE_LIMIT_WRITE_PER_MINUTE", "5");
            env.put("RATE_LIMIT_READ_PER_MINUTE", "1000000");
            try (TestServer server = TestServer.start(null, env)) {
                int admitted = 0;
                Helpers.Response limited = null;
                for (int i = 0; i < 40; i++) {
                    Helpers.Response r = Helpers.requestTo(server.baseUrl, null, "POST", BASE, Map.of());
                    if (r.status == 429) { limited = r; break; }
                    admitted++;
                }
                Assert.isTrue(admitted >= 1, "at least one write should be admitted before limiting");
                Assert.isTrue(limited != null, "writes should eventually be rate limited");
                int retryAfter = Integer.parseInt(limited.headers.get("retry-after"));
                Assert.isTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After out of range: " + limited.headers.get("retry-after"));
                Assert.equal(429, limited.status);
                Assert.equal("TOO_MANY_REQUESTS", ((Map<?, ?>) limited.body).get("error"));
            }
        });

        ctx.test("reads have their own window, independent of the write limit", () -> {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("RATE_LIMIT_READ_PER_MINUTE", "120");
            env.put("RATE_LIMIT_WRITE_PER_MINUTE", "1000000");
            try (TestServer server = TestServer.start(null, env)) {
                int admitted = 0;
                Helpers.Response limited = null;
                for (int i = 0; i < 200; i++) {
                    Helpers.Response r = Helpers.requestTo(server.baseUrl, null, "GET", BASE, null);
                    if (r.status == 429) { limited = r; break; }
                    admitted++;
                }
                Assert.isTrue(admitted >= 1, "at least one read should be admitted before limiting");
                Assert.isTrue(limited != null, "reads should eventually be rate limited");
                int retryAfter = Integer.parseInt(limited.headers.get("retry-after"));
                Assert.isTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After out of range: " + limited.headers.get("retry-after"));
                Assert.equal(429, limited.status);
                Assert.equal("TOO_MANY_REQUESTS", ((Map<?, ?>) limited.body).get("error"));
            }
        });
    }
}
