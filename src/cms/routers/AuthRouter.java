package cms.routers;

import cms.Auth;
import cms.Errors;
import cms.Http;
import cms.Router;
import cms.Sessions;
import cms.models.Account;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal auth routes outside the entity CRUD path: login, logout and me. The
 * API is the auth authority and speaks Bearer only; the cookie and CSRF transport
 * is the admin frontend's concern. The principal is attached by the middleware
 * filter before routing; login is reachable anonymously, logout and me need a
 * live session.
 */
public final class AuthRouter implements Router {

    private static final String BASE = "/auth";
    private static final Pattern BEARER = Pattern.compile("^Bearer (.+)$");

    private static String bearerToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) return null;
        Matcher m = BEARER.matcher(header.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static Map<String, Object> accountView(Object id, Object username, Object role) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("username", username);
        view.put("role", role);
        return view;
    }

    @Override
    public boolean dispatch(HttpExchange exchange, String method, String path, String requestPath, Auth.Principal principal) throws IOException {
        if ((BASE + "/login").equals(path)) {
            if (!"POST".equals(method)) {
                Http.jsonError(exchange, Errors.methodNotAllowed(List.of("POST"), requestPath));
                return true;
            }
            Map<String, Object> body = Http.parseBody(exchange);
            Object username = body.get("username");
            Object password = body.get("password");
            if (!(username instanceof String) || !(password instanceof String)) {
                Http.jsonError(exchange, Errors.validation(List.of("Fields \"username\" and \"password\" are required."), requestPath));
                return true;
            }
            // Same 401 for unknown user and wrong password — no user enumeration.
            Map<String, Object> account = Account.authenticate((String) username, (String) password);
            if (account == null) {
                Http.jsonError(exchange, Errors.unauthorized(requestPath));
                return true;
            }
            Sessions.Issued issued = Sessions.createSession((String) account.get("id"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", issued.token());
            out.put("account", accountView(account.get("id"), account.get("username"), account.get("role")));
            out.put("expiresAt", issued.expiresAt());
            Http.json(exchange, 200, out);
            return true;
        }

        if ((BASE + "/logout").equals(path)) {
            if (!"POST".equals(method)) {
                Http.jsonError(exchange, Errors.methodNotAllowed(List.of("POST"), requestPath));
                return true;
            }
            // Idempotent by token: a missing or already-deleted token is 401.
            String token = bearerToken(exchange);
            boolean removed = token != null && Sessions.deleteSession(token);
            if (!removed) {
                Http.jsonError(exchange, Errors.unauthorized(requestPath));
                return true;
            }
            Http.json(exchange, 204, null);
            return true;
        }

        if ((BASE + "/me").equals(path)) {
            if (!"GET".equals(method)) {
                Http.jsonError(exchange, Errors.methodNotAllowed(List.of("GET"), requestPath));
                return true;
            }
            if ("anonymous".equals(principal.role())) {
                Http.jsonError(exchange, Errors.unauthorized(requestPath));
                return true;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("account", accountView(principal.accountId(), principal.username(), principal.role()));
            Http.json(exchange, 200, out);
            return true;
        }

        return false;
    }
}
