package cms.test;

import com.sun.net.httpserver.HttpServer;
import cms.Server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TestRunner {

    public static final AtomicInteger PASS = new AtomicInteger(0);
    public static final AtomicInteger FAIL = new AtomicInteger(0);

    public static int findFreePort() {
        for (int port = 14000; port < 16000; port++) {
            try (Socket s = new Socket()) {
                s.bind(new InetSocketAddress("127.0.0.1", port));
                return port;
            } catch (IOException ignored) {
                // try next
            }
        }
        throw new RuntimeException("No free port found");
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("cms-java-test-");
        // Storage reads DATA_DIR via System.getenv which we cannot mutate from Java;
        // use ProcessBuilder for the server with the proper environment instead.

        int port = findFreePort();
        ProcessBuilder pb = new ProcessBuilder("java", "-cp", "out", "cms.Server");
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("DATA_DIR", dataDir.toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process srv = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            srv.destroy();
            try { srv.waitFor(); } catch (InterruptedException ignored) {}
            deleteRecursive(dataDir);
        }));

        String baseUrl = "http://127.0.0.1:" + port;
        Helpers.setBase(baseUrl);
        if (!Helpers.waitForHealth(10_000)) {
            srv.destroy();
            System.err.println("Server did not become healthy");
            System.exit(2);
        }

        TestContext ctx = new TestContext();

        run("BlogPosting", cms.test.BlogPostingTest::run, ctx);
        run("Person", cms.test.PersonTest::run, ctx);
        run("WebPage", cms.test.WebPageTest::run, ctx);
        run("ImageObject", cms.test.ImageObjectTest::run, ctx);
        run("CategoryCode", cms.test.CategoryCodeTest::run, ctx);
        run("CategoryCodeSet", cms.test.CategoryCodeSetTest::run, ctx);
        run("DefinedTerm", cms.test.DefinedTermTest::run, ctx);
        run("DefinedTermSet", cms.test.DefinedTermSetTest::run, ctx);
        run("Comment", cms.test.CommentTest::run, ctx);
        run("WebSite", cms.test.WebSiteTest::run, ctx);

        int total = PASS.get() + FAIL.get();
        System.out.println();
        System.out.println("# tests " + total);
        System.out.println("# pass " + PASS.get());
        System.out.println("# fail " + FAIL.get());
        srv.destroy();
        srv.waitFor();
        deleteRecursive(dataDir);
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

    private static void deleteRecursive(Path path) {
        try {
            if (!Files.exists(path)) return;
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
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
