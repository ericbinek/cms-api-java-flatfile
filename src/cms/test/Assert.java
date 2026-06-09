package cms.test;

public final class Assert {

    private Assert() {}

    public static class AssertionException extends RuntimeException {
        public AssertionException(String msg) { super(msg); }
    }

    public static void isTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionException(msg);
    }

    public static void equal(Object expected, Object actual) {
        equal(expected, actual, "");
    }

    public static void equal(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            String prefix = msg.isEmpty() ? "" : msg + ": ";
            throw new AssertionException(prefix + "expected " + repr(expected) + ", got " + repr(actual));
        }
    }

    public static void match(java.util.regex.Pattern pattern, String haystack, String msg) {
        if (!pattern.matcher(haystack).find()) {
            String prefix = msg.isEmpty() ? "" : msg + ": ";
            throw new AssertionException(prefix + "expected pattern " + pattern + " to match");
        }
    }

    private static String repr(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "\"" + o + "\"";
        return o.toString();
    }
}
