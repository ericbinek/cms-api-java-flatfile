package cms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class Storage {

    private static Path dataDir;
    private static final ReentrantLock LOCK = new ReentrantLock();

    private Storage() {}

    public static synchronized Path dataDir() {
        if (dataDir == null) {
            String d = System.getenv("DATA_DIR");
            Path p = (d != null && !d.isEmpty()) ? Path.of(d) : Path.of("./data");
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new RuntimeException("Cannot create data directory: " + p, e);
            }
            dataDir = p.toAbsolutePath();
        }
        return dataDir;
    }

    public static List<Map<String, Object>> readCollection(String filename) {
        Path path = dataDir().resolve(filename);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            String body = Files.readString(path, StandardCharsets.UTF_8);
            if (body.isEmpty()) return new ArrayList<>();
            Object parsed = Json.parse(body);
            if (!(parsed instanceof List)) return new ArrayList<>();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : (List<?>) parsed) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) item;
                    out.add(m);
                }
            }
            return out;
        } catch (Json.JsonException e) {
            throw new RuntimeException("Data file corrupted: " + path);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read data file: " + path);
        }
    }

    public static void writeCollection(String filename, List<Map<String, Object>> items) {
        Path path = dataDir().resolve(filename);
        Path tmp = path.resolveSibling(filename + ".tmp");
        try {
            Files.writeString(tmp, Json.stringify(items), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write data file: " + path, e);
        }
    }

    public static <T> T withLock(Supplier<T> fn) {
        LOCK.lock();
        try {
            return fn.get();
        } finally {
            LOCK.unlock();
        }
    }
}
