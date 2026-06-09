package cms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Validation {

    public static final int MAX_STRING_LENGTH = 100_000;

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile(
        "^https?://\\S+$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATETIME_PATTERN = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?(Z|[+-]\\d{2}:\\d{2})$");

    private static final Set<String> DANGEROUS_KEYS = Set.of("__proto__", "constructor", "prototype");

    private Validation() {}

    public static boolean isDangerousKey(String k) {
        return DANGEROUS_KEYS.contains(k);
    }

    public static String sanitizeString(String v) {
        String stripped = v.replace("\u0000", "");
        return Normalizer.normalize(stripped, Normalizer.Form.NFC);
    }

    public static Object deepSanitize(Object value) {
        if (value instanceof String) return sanitizeString((String) value);
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<?>) value) out.add(deepSanitize(v));
            return out;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String k = e.getKey().toString();
                if (isDangerousKey(k)) continue;
                out.put(k, deepSanitize(e.getValue()));
            }
            return out;
        }
        return value;
    }

    public static boolean isValidUuid(Object s) {
        return s instanceof String && UUID_PATTERN.matcher((String) s).matches();
    }

    public static Object normalizeUuid(Object s) {
        return s instanceof String ? ((String) s).toLowerCase() : s;
    }

    public static boolean checkScalar(String type, Object value) {
        switch (type) {
            case "Integer":
                if (value instanceof Long || value instanceof Integer) return true;
                if (value instanceof Double) {
                    double d = (Double) value;
                    return Double.isFinite(d) && d == Math.floor(d);
                }
                return false;
            case "Number":
                if (value instanceof Number) {
                    double d = ((Number) value).doubleValue();
                    return Double.isFinite(d);
                }
                return false;
            case "Boolean":
                return value instanceof Boolean;
            case "Date":
            case "DateTime":
            case "Time":
                return value instanceof String && ISO_DATETIME_PATTERN.matcher((String) value).matches();
            case "URL":
                return value instanceof String && HTTP_URL_PATTERN.matcher((String) value).matches();
            default:
                return value instanceof String && ((String) value).length() <= MAX_STRING_LENGTH;
        }
    }

    public static boolean isEmbed(Object v, String type) {
        if (!(v instanceof Map)) return false;
        return type.equals(((Map<?, ?>) v).get("@type"));
    }

    public static String etagFor(Object item) {
        return etagFor(Json.stringify(item).getBytes(StandardCharsets.UTF_8));
    }

    public static String etagFor(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i] & 0xff));
            sb.append("\"");
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateUuid() {
        return UUID.randomUUID().toString();
    }
}
