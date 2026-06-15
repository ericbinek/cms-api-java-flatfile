package cms.test;

import cms.Json;
import cms.Validation;
import cms.models.Account;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TestServer implements AutoCloseable {

    public final String baseUrl;
    private final Process process;
    private final Path dataDir;

    private TestServer(String baseUrl, Process process, Path dataDir) {
        this.baseUrl = baseUrl;
        this.process = process;
        this.dataDir = dataDir;
    }

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

    // Builds a stored account record (hashed password) for direct seeding into
    // accounts.json — the account API does not create non-admin accounts in V1.
    public static Map<String, Object> account(String username, String password, String role) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", Validation.generateUuid());
        record.put("username", username);
        record.put("passwordHash", Account.hashPassword(password));
        record.put("role", role);
        return record;
    }

    // accounts == null leaves the store unseeded (exercises the env bootstrap or an
    // empty store); env may be null. The returned server is healthy.
    public static TestServer start(List<Map<String, Object>> accounts, Map<String, String> env) {
        try {
            Path dataDir = Files.createTempDirectory("cms-java-auth-");
            if (accounts != null) {
                Files.writeString(dataDir.resolve("accounts.json"), Json.stringify(accounts), StandardCharsets.UTF_8);
            }
            int port = findFreePort();
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", "out", "cms.Server");
            pb.environment().put("PORT", String.valueOf(port));
            pb.environment().put("DATA_DIR", dataDir.toString());
            if (env != null) pb.environment().putAll(env);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            String baseUrl = "http://127.0.0.1:" + port;
            if (!waitForHealth(baseUrl, 10_000)) {
                process.destroy();
                deleteRecursive(dataDir);
                throw new RuntimeException("Server did not become healthy: " + baseUrl);
            }
            return new TestServer(baseUrl, process, dataDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start test server", e);
        }
    }

    private static boolean waitForHealth(String baseUrl, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Helpers.Response r = Helpers.requestTo(baseUrl, null, "GET", "/health", null);
                if (r.status == 200) return true;
            } catch (Exception ignored) {
                // server not up yet
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    @Override
    public void close() {
        process.destroy();
        try { process.waitFor(); } catch (InterruptedException ignored) {}
        deleteRecursive(dataDir);
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
}
