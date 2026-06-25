package cms;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Per-IP sliding-window rate limiter, applied as an httpserver Filter that runs
 * before routing. Two independent one-minute windows per client: reads (GET/HEAD
 * and any non-write method) and writes (POST/PUT/DELETE). State lives in process
 * memory, matching the single-process model — counts are not shared across
 * instances. The peer address of the connection is the only trusted source; an
 * X-Forwarded-For header is never consulted. The server is threaded, so all
 * access to the shared state is guarded by a single lock.
 */
public final class RateLimit {

    private static final long WINDOW_MS = 60_000L;
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE");

    private static final int READ_LIMIT = limitFromEnv("RATE_LIMIT_READ_PER_MINUTE", 600);
    private static final int WRITE_LIMIT = limitFromEnv("RATE_LIMIT_WRITE_PER_MINUTE", 60);

    private RateLimit() {}

    private static int limitFromEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class Windows {
        final ArrayDeque<Long> read = new ArrayDeque<>();
        final ArrayDeque<Long> write = new ArrayDeque<>();
    }

    private static final Map<String, Windows> HITS = new HashMap<>();
    private static final Object LOCK = new Object();
    private static long lastSweep = 0L;

    private static void prune(ArrayDeque<Long> stamps, long cutoff) {
        while (!stamps.isEmpty() && stamps.peekFirst() <= cutoff) {
            stamps.pollFirst();
        }
    }

    // Drops aged-out timestamps across all clients and forgets idle ones, so memory
    // stays bounded by the clients active in the last window. Runs at most once per
    // window, piggybacked on a request under the lock — no background thread.
    private static void sweep(long now, long cutoff) {
        if (now - lastSweep < WINDOW_MS) {
            return;
        }
        lastSweep = now;
        Iterator<Map.Entry<String, Windows>> it = HITS.entrySet().iterator();
        while (it.hasNext()) {
            Windows w = it.next().getValue();
            prune(w.read, cutoff);
            prune(w.write, cutoff);
            if (w.read.isEmpty() && w.write.isEmpty()) {
                it.remove();
            }
        }
    }

    // Records a request from ip with the given method. Returns null when within the
    // bucket's limit, otherwise the whole seconds until the oldest in-window request
    // expires (at least 1) — the Retry-After value.
    public static Integer check(String ip, String method) {
        boolean write = WRITE_METHODS.contains(method);
        int limit = write ? WRITE_LIMIT : READ_LIMIT;
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;
        synchronized (LOCK) {
            sweep(now, cutoff);
            Windows w = HITS.computeIfAbsent(ip, k -> new Windows());
            ArrayDeque<Long> stamps = write ? w.write : w.read;
            prune(stamps, cutoff);
            if (stamps.size() >= limit) {
                long retryMs = stamps.peekFirst() + WINDOW_MS - now;
                return (int) Math.max(1L, (retryMs + 999L) / 1000L);
            }
            stamps.addLast(now);
            return null;
        }
    }

    public static Filter filter() {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                String ip = exchange.getRemoteAddress().getHostString();
                Integer retryAfter = check(ip, exchange.getRequestMethod());
                if (retryAfter != null) {
                    String requestPath = Http.requestPath(exchange);
                    Http.json(exchange, 429, Errors.tooManyRequests(requestPath),
                        Map.of("Retry-After", String.valueOf(retryAfter)));
                    exchange.close();
                    return;
                }
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return "rate-limit";
            }
        };
    }
}
