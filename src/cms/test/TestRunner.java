package cms.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TestRunner {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "test-suite-admin-secret";

    public static final AtomicInteger PASS = new AtomicInteger(0);
    public static final AtomicInteger FAIL = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        // Shared server for the entity suite. Writes now require a session, so the
        // server bootstraps an admin from the environment and the suite drives the
        // API as that admin. The auth conformance suite manages its own servers.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ADMIN_USER", ADMIN_USER);
        env.put("ADMIN_PASSWORD", ADMIN_PASSWORD);
        TestServer server = TestServer.start(null, env);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        Helpers.setBase(server.baseUrl);
        Helpers.setAuthToken(Helpers.login(server.baseUrl, ADMIN_USER, ADMIN_PASSWORD));

        TestContext ctx = new TestContext();

        run("BlogPosting", cms.test.BlogPostingTest::run, ctx);
        run("Person", cms.test.PersonTest::run, ctx);
        run("Organization", cms.test.OrganizationTest::run, ctx);
        run("WebPage", cms.test.WebPageTest::run, ctx);
        run("ImageObject", cms.test.ImageObjectTest::run, ctx);
        run("VideoObject", cms.test.VideoObjectTest::run, ctx);
        run("AudioObject", cms.test.AudioObjectTest::run, ctx);
        run("CategoryCode", cms.test.CategoryCodeTest::run, ctx);
        run("CategoryCodeSet", cms.test.CategoryCodeSetTest::run, ctx);
        run("DefinedTerm", cms.test.DefinedTermTest::run, ctx);
        run("DefinedTermSet", cms.test.DefinedTermSetTest::run, ctx);
        run("Comment", cms.test.CommentTest::run, ctx);
        run("WebSite", cms.test.WebSiteTest::run, ctx);
        run("SiteNavigationElement", cms.test.SiteNavigationElementTest::run, ctx);
        run("Auth", AuthConformanceTest::run, ctx);

        int total = PASS.get() + FAIL.get();
        System.out.println();
        System.out.println("# tests " + total);
        System.out.println("# pass " + PASS.get());
        System.out.println("# fail " + FAIL.get());
        server.close();
        System.exit(FAIL.get() > 0 ? 1 : 0);
    }

    private static void run(String entity, Consumer<TestContext> tests, TestContext ctx) {
        ctx.currentEntity = entity;
        tests.accept(ctx);
    }

    public static void recordPass(String name) {
        System.out.println("ok - " + name);
        PASS.incrementAndGet();
    }

    public static void recordFail(String name, Throwable e) {
        System.out.println("not ok - " + name);
        System.out.println("  " + e.getMessage());
        for (StackTraceElement el : e.getStackTrace()) {
            System.out.println("  at " + el);
        }
        FAIL.incrementAndGet();
    }

    public static class TestContext {
        public String currentEntity;

        public void test(String name, ThrowingRunnable fn) {
            String fullName = currentEntity + ": " + name;
            try {
                fn.run();
                recordPass(fullName);
            } catch (Throwable e) {
                recordFail(fullName, e);
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
