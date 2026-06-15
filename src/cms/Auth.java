package cms;

import cms.models.Account;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auth middleware. Resolves the request principal from the Authorization header
 * and runs before routing as an httpserver Filter: a presented but invalid
 * credential is 401, a missing credential is the anonymous principal, and a write
 * without a session is 401 (no role grants anonymous writes). The 403 guards
 * (permission, ownership, workflow) live per operation in the entity routers.
 */
public final class Auth {

    public static final String PRINCIPAL_ATTR = "cms.principal";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern BEARER = Pattern.compile("^Bearer (.+)$");

    private Auth() {}

    /** The authenticated identity for a request, or the anonymous default. */
    public record Principal(String role, String accountId, String username) {}

    public static final Principal ANONYMOUS = new Principal("anonymous", null, null);

    /**
     * Thrown when a credential is presented but does not resolve. The filter maps
     * it to 401 UNAUTHORIZED. A missing credential is not an error — it is anonymous.
     */
    public static final class UnauthorizedException extends RuntimeException {
        public UnauthorizedException() { super("Authentication required."); }
    }

    // null = no header (anonymous); "" = malformed header (invalid); else the token.
    private static String bearerToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) return null;
        Matcher m = BEARER.matcher(header.trim());
        return m.matches() ? m.group(1) : "";
    }

    // Resolves the request principal. No Authorization header -> anonymous. A
    // Bearer token that does not resolve to a live session (or a malformed header)
    // throws UnauthorizedException. Fails closed: a presented credential must be valid.
    public static Principal resolvePrincipal(HttpExchange exchange) {
        String token = bearerToken(exchange);
        if (token == null) return ANONYMOUS;
        if (token.isEmpty()) throw new UnauthorizedException();
        Sessions.Active session = Sessions.resolveSession(token);
        if (session == null) throw new UnauthorizedException();
        Map<String, Object> account = Account.findById(session.accountId());
        if (account == null) throw new UnauthorizedException();
        return new Principal((String) account.get("role"), (String) account.get("id"), (String) account.get("username"));
    }

    // A write method by an anonymous principal needs a session: 401 (the router's
    // 403 covers an authenticated-but-unauthorized principal).
    public static boolean requiresSession(String method, Principal principal) {
        return WRITE_METHODS.contains(method) && "anonymous".equals(principal.role());
    }

    /** Reads the principal the filter attached to the exchange before routing. */
    public static Principal of(HttpExchange exchange) {
        Object principal = exchange.getAttribute(PRINCIPAL_ATTR);
        return principal instanceof Principal ? (Principal) principal : ANONYMOUS;
    }

    // The middleware filter. Resolves the principal (401 for a presented but invalid
    // credential) and, when gateWrites is set, rejects an anonymous write with 401
    // before routing. On success it attaches the principal and proceeds.
    public static Filter filter(boolean gateWrites) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                String requestPath = Http.requestPath(exchange);
                Principal principal;
                try {
                    principal = resolvePrincipal(exchange);
                } catch (UnauthorizedException e) {
                    Http.jsonError(exchange, Errors.unauthorized(requestPath));
                    exchange.close();
                    return;
                } catch (Exception e) {
                    System.err.println("[" + requestPath + "] " + e.getMessage());
                    Http.jsonError(exchange, Errors.internal(requestPath));
                    exchange.close();
                    return;
                }
                if (gateWrites && requiresSession(exchange.getRequestMethod(), principal)) {
                    Http.jsonError(exchange, Errors.unauthorized(requestPath));
                    exchange.close();
                    return;
                }
                exchange.setAttribute(PRINCIPAL_ATTR, principal);
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return gateWrites ? "auth-guard" : "auth-principal";
            }
        };
    }
}
