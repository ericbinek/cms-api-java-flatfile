package cms.models;

import cms.Storage;
import cms.Validation;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Internal account store. Not a schema.org entity: it never travels the public
 * CRUD path and never appears in the entity API. Passwords are hashed with
 * PBKDF2-HMAC-SHA256, a built-in, salted, slow KDF. The stored string is self
 * describing (algo, digest, iterations, salt, hash) so a future cost bump can
 * verify old hashes and rehash on next login.
 */
public final class Account {

    public static final String COLLECTION_FILE = "accounts.json";

    private static final int ITERATIONS = 210000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final String DIGEST = "sha256";
    private static final SecureRandom RNG = new SecureRandom();

    private Account() {}

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 derivation failed", e);
        }
    }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, ITERATIONS, KEY_LENGTH_BITS);
        return "pbkdf2$" + DIGEST + "$" + ITERATIONS + "$" + toHex(salt) + "$" + toHex(derived);
    }

    public static boolean verifyPassword(String password, String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 5 || !"pbkdf2".equals(parts[0])) return false;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (iterations < 1) return false;
        byte[] salt = fromHex(parts[3]);
        byte[] expected = fromHex(parts[4]);
        byte[] actual = pbkdf2(password, salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(expected, actual);
    }

    // A dummy hash kept so an unknown username still runs one PBKDF2 verification:
    // the response time does not reveal whether the username existed.
    private static final String DUMMY_HASH = hashPassword(randomHex());

    public static Map<String, Object> findByUsername(String username) {
        for (Map<String, Object> account : Storage.readCollection(COLLECTION_FILE)) {
            if (username != null && username.equals(account.get("username"))) return account;
        }
        return null;
    }

    public static Map<String, Object> findById(String id) {
        for (Map<String, Object> account : Storage.readCollection(COLLECTION_FILE)) {
            if (id != null && id.equals(account.get("id"))) return account;
        }
        return null;
    }

    public static Map<String, Object> authenticate(String username, String password) {
        Map<String, Object> account = findByUsername(username);
        String hash = account != null ? (String) account.get("passwordHash") : DUMMY_HASH;
        boolean ok = verifyPassword(password, hash);
        return ok && account != null ? account : null;
    }

    public static Map<String, Object> createAccount(String username, String password, String role) {
        return Storage.withLock(() -> {
            List<Map<String, Object>> accounts = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            for (Map<String, Object> existing : accounts) {
                if (username.equals(existing.get("username"))) {
                    throw new RuntimeException("Account already exists: " + username);
                }
            }
            Map<String, Object> account = record(username, password, role);
            accounts.add(account);
            Storage.writeCollection(COLLECTION_FILE, accounts);
            return account;
        });
    }

    // Bootstrap: with an empty store and ADMIN_USER/ADMIN_PASSWORD set, the first
    // start creates a single admin. Idempotent — a populated store is a no-op, and
    // missing env vars leave the store empty (every protected write then 401s).
    public static Map<String, Object> seedAdmin() {
        return Storage.withLock(() -> {
            String user = System.getenv("ADMIN_USER");
            String password = System.getenv("ADMIN_PASSWORD");
            if (user == null || user.isEmpty() || password == null || password.isEmpty()) return null;
            List<Map<String, Object>> accounts = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            if (!accounts.isEmpty()) return null;
            Map<String, Object> account = record(user, password, "admin");
            Storage.writeCollection(COLLECTION_FILE, List.of(account));
            return account;
        });
    }

    private static Map<String, Object> record(String username, String password, String role) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", Validation.generateUuid());
        account.put("username", username);
        account.put("passwordHash", hashPassword(password));
        account.put("role", role);
        return account;
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        RNG.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
