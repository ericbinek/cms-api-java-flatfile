package cms;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal session store. Per login a secure-random opaque token is issued; the
 * store (sessions.json) keeps only its SHA-256 hash (a token is treated like a
 * password), the account, the absolute expiry and a sliding idle expiry. Logout
 * and revocation are a store delete and take effect immediately.
 */
public final class Sessions {

    public static final String COLLECTION_FILE = "sessions.json";

    private static final long IDLE_TTL_MS = 30L * 60 * 1000;          // sliding inactivity window
    private static final long ABSOLUTE_TTL_MS = 8L * 60 * 60 * 1000;  // hard cap measured from login
    private static final long EXTEND_THRESHOLD_MS = 60L * 1000;       // only persist a slide worth writing

    private static final SecureRandom RNG = new SecureRandom();

    private Sessions() {}

    /** The raw token, returned exactly once at login; the store keeps only its hash. */
    public record Issued(String token, String expiresAt) {}

    /** A resolved live session: the owning account and the absolute expiry. */
    public record Active(String accountId, String expiresAt) {}

    private static String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    // Issues a session. The raw token is returned exactly once; the store keeps
    // only its SHA-256 hash, the account, the absolute expiry and the idle expiry.
    public static Issued createSession(String accountId) {
        return Storage.withLock(() -> {
            byte[] raw = new byte[32];
            RNG.nextBytes(raw);
            StringBuilder tokenBuilder = new StringBuilder(raw.length * 2);
            for (byte b : raw) tokenBuilder.append(String.format("%02x", b & 0xff));
            String token = tokenBuilder.toString();

            List<Map<String, Object>> sessions = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            Instant now = Instant.now();
            String expiresAt = now.plusMillis(ABSOLUTE_TTL_MS).toString();
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("tokenHash", hashToken(token));
            session.put("accountId", accountId);
            session.put("createdAt", now.toString());
            session.put("expiresAt", expiresAt);
            session.put("idleExpiresAt", now.plusMillis(IDLE_TTL_MS).toString());
            sessions.add(session);
            Storage.writeCollection(COLLECTION_FILE, sessions);
            return new Issued(token, expiresAt);
        });
    }

    // Resolves a raw token to its live session, or null if unknown or expired. An
    // expired session is dropped. On success the idle window slides forward (capped
    // at the absolute expiry) and is persisted only when the move is large enough,
    // so authenticated reads do not write on every request.
    public static Active resolveSession(String token) {
        return Storage.withLock(() -> {
            String tokenHash = hashToken(token);
            List<Map<String, Object>> sessions = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            int index = -1;
            for (int i = 0; i < sessions.size(); i++) {
                if (tokenHash.equals(sessions.get(i).get("tokenHash"))) { index = i; break; }
            }
            if (index < 0) return null;

            Map<String, Object> session = sessions.get(index);
            Instant now = Instant.now();
            Instant absolute = parseInstant((String) session.get("expiresAt"));
            Instant idle = parseInstant((String) session.get("idleExpiresAt"));
            if (absolute == null || idle == null || !now.isBefore(absolute) || !now.isBefore(idle)) {
                sessions.remove(index);
                Storage.writeCollection(COLLECTION_FILE, sessions);
                return null;
            }

            Instant nextIdle = now.plusMillis(IDLE_TTL_MS);
            if (nextIdle.isAfter(absolute)) nextIdle = absolute;
            if (nextIdle.toEpochMilli() - idle.toEpochMilli() > EXTEND_THRESHOLD_MS) {
                session.put("idleExpiresAt", nextIdle.toString());
                Storage.writeCollection(COLLECTION_FILE, sessions);
            }
            return new Active((String) session.get("accountId"), (String) session.get("expiresAt"));
        });
    }

    // Logout / revocation: deletes the session and takes effect immediately.
    public static boolean deleteSession(String token) {
        return Storage.withLock(() -> {
            String tokenHash = hashToken(token);
            List<Map<String, Object>> sessions = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            List<Map<String, Object>> remaining = new ArrayList<>();
            for (Map<String, Object> session : sessions) {
                if (!tokenHash.equals(session.get("tokenHash"))) remaining.add(session);
            }
            boolean removed = remaining.size() != sessions.size();
            if (removed) Storage.writeCollection(COLLECTION_FILE, remaining);
            return removed;
        });
    }

    private static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
