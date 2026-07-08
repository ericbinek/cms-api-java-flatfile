package cms.routers;

import cms.Access;
import cms.Auth;
import cms.Errors;
import cms.Http;
import cms.Router;
import cms.models.ImageObject;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ImageObjectRouter implements Router {

    public static final String ENTITY = "ImageObject";
    public static final String BASE = "/image-objects";
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;
    private static final Set<String> SYSTEM_FILTER_KEYS = Set.of("limit", "offset", "sort", "order");

    @Override
    public boolean dispatch(HttpExchange exchange, String method, String path, String requestPath, Auth.Principal principal) throws Exception {
        if (BASE.equals(path)) {
            handleCollection(exchange, method, requestPath, principal);
            return true;
        }
        if (path.startsWith(BASE + "/")) {
            String rest = path.substring(BASE.length() + 1);
            if (rest.contains("/")) return false;
            handleItem(exchange, method, rest, requestPath, principal);
            return true;
        }
        return false;
    }

    private static Map<String, List<String>> parseQuery(URI uri) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return out;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? decode(pair) : decode(pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(k, key -> new ArrayList<>()).add(v);
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static class ListOptions {
        int limit = DEFAULT_LIMIT;
        int offset = 0;
        String sort = "dateCreated";
        String order = "desc";
        Map<String, Object> filter = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
    }

    private static ListOptions parseListOptions(HttpExchange exchange) {
        ListOptions opts = new ListOptions();
        Map<String, List<String>> qs = parseQuery(exchange.getRequestURI());
        if (qs.containsKey("limit")) {
            try {
                int n = Integer.parseInt(qs.get("limit").get(0));
                if (n < 1 || n > MAX_LIMIT) {
                    opts.errors.add("Query \"limit\" must be an integer between 1 and " + MAX_LIMIT + ".");
                } else {
                    opts.limit = n;
                }
            } catch (NumberFormatException e) {
                opts.errors.add("Query \"limit\" must be an integer between 1 and " + MAX_LIMIT + ".");
            }
        }
        if (qs.containsKey("offset")) {
            try {
                int n = Integer.parseInt(qs.get("offset").get(0));
                if (n < 0) opts.errors.add("Query \"offset\" must be a non-negative integer.");
                else opts.offset = n;
            } catch (NumberFormatException e) {
                opts.errors.add("Query \"offset\" must be a non-negative integer.");
            }
        }
        if (qs.containsKey("sort")) {
            String v = qs.get("sort").get(0);
            if (!ImageObject.SORTABLE_FIELDS.contains(v)) {
                List<String> sorted = new ArrayList<>(ImageObject.SORTABLE_FIELDS);
                java.util.Collections.sort(sorted);
                opts.errors.add("Query \"sort\" must be one of: " + String.join(", ", sorted) + ".");
            } else {
                opts.sort = v;
            }
        }
        if (qs.containsKey("order")) {
            String v = qs.get("order").get(0);
            if (!"asc".equals(v) && !"desc".equals(v)) opts.errors.add("Query \"order\" must be \"asc\" or \"desc\".");
            else opts.order = v;
        }
        for (Map.Entry<String, List<String>> e : qs.entrySet()) {
            if (SYSTEM_FILTER_KEYS.contains(e.getKey())) continue;
            if (!ImageObject.SEARCHABLE_FIELDS.contains(e.getKey())) {
                opts.errors.add("Unknown filter field \"" + e.getKey() + "\".");
                continue;
            }
            opts.filter.put(e.getKey(), e.getValue().get(0));
        }
        return opts;
    }

    @SuppressWarnings("unchecked")
    private void handleCollection(HttpExchange exchange, String method, String requestPath, Auth.Principal principal) throws IOException {
        String role = principal.role();
        if ("GET".equals(method)) {
            if (!Access.can(role, ENTITY, "read")) {
                Http.jsonError(exchange, Errors.forbidden("Role \"" + role + "\" may not read " + ENTITY + ".", requestPath));
                return;
            }
            ListOptions opts = parseListOptions(exchange);
            if (!opts.errors.isEmpty()) {
                Http.jsonError(exchange, Errors.validation(opts.errors, requestPath));
                return;
            }
            // Apply read visibility on the full filtered set, then paginate, so total
            // counts only the records this principal may see. Internal fields stripped.
            Map<String, Object> all = ImageObject.findAll(opts.filter, opts.sort, opts.order, Integer.MAX_VALUE, 0);
            List<Map<String, Object>> allItems = (List<Map<String, Object>>) all.get("items");
            List<Map<String, Object>> visible = new ArrayList<>();
            for (Map<String, Object> item : allItems) {
                if (Access.isVisible(role, ENTITY, item)) visible.add(item);
            }
            int from = Math.min(opts.offset, visible.size());
            int to = Math.min(from + opts.limit, visible.size());
            List<Object> page = new ArrayList<>();
            for (Map<String, Object> item : visible.subList(from, to)) page.add(Access.stripFields(role, item));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("items", page);
            envelope.put("total", visible.size());
            Http.json(exchange, 200, envelope);
            return;
        }
        if ("POST".equals(method)) {
            if (!Access.can(role, ENTITY, "create")) {
                Http.jsonError(exchange, Errors.forbidden("Role \"" + role + "\" may not create " + ENTITY + ".", requestPath));
                return;
            }
            Map<String, Object> body = ImageObject.sanitize(Http.parseBody(exchange));
            List<String> readonly = Access.readonlyViolations(role, body);
            if (!readonly.isEmpty()) {
                Http.jsonError(exchange, Errors.validation(List.of("Fields are not writable: " + String.join(", ", readonly) + "."), requestPath));
                return;
            }
            List<String> errs = ImageObject.validate(body, false);
            if (!errs.isEmpty()) {
                Http.jsonError(exchange, Errors.validation(errs, requestPath));
                return;
            }
            Map<String, Object> created = ImageObject.create(Access.applyCreateDefaults(ENTITY, body, principal.accountId()));
            Http.json(exchange, 201, Access.stripFields(role, created), Map.of("Location", BASE + "/" + created.get("id")), ImageObject.etagOf(created));
            return;
        }
        Http.jsonError(exchange, Errors.methodNotAllowed(List.of("GET", "POST"), requestPath));
    }

    private void handleItem(HttpExchange exchange, String method, String id, String requestPath, Auth.Principal principal) throws IOException {
        String role = principal.role();
        if (!Http.isValidUuid(id)) {
            Http.jsonError(exchange, Errors.invalidId(requestPath));
            return;
        }
        if ("GET".equals(method)) {
            if (!Access.can(role, ENTITY, "read")) {
                Http.jsonError(exchange, Errors.forbidden("Role \"" + role + "\" may not read " + ENTITY + ".", requestPath));
                return;
            }
            Map<String, Object> item = ImageObject.findById(id);
            // A record the principal may not see is indistinguishable from a missing
            // one (404, never 403) so its existence is not disclosed.
            if (item == null || !Access.isVisible(role, ENTITY, item)) {
                Http.jsonError(exchange, Errors.notFound(ImageObject.TYPE_NAME, requestPath));
                return;
            }
            // Single-resource GET embeds referenced entities one level deep
            // (JSON-LD style); list responses and POST responses stay flat.
            // The ETag names the stored record's version, not the role- and
            // embedding-shaped body — it must satisfy a later If-Match.
            Http.json(exchange, 200, Access.stripFields(role, ImageObject.embedRefs(item)), null, ImageObject.etagOf(item));
            return;
        }
        if ("PUT".equals(method)) {
            if (!Access.can(role, ENTITY, "update")) {
                Http.jsonError(exchange, Errors.forbidden("Role \"" + role + "\" may not update " + ENTITY + ".", requestPath));
                return;
            }
            Map<String, Object> body = ImageObject.sanitize(Http.parseBody(exchange));
            List<String> readonly = Access.readonlyViolations(role, body);
            if (!readonly.isEmpty()) {
                Http.jsonError(exchange, Errors.validation(List.of("Fields are not writable: " + String.join(", ", readonly) + "."), requestPath));
                return;
            }
            List<String> errs = ImageObject.validate(body, true);
            if (!errs.isEmpty()) { Http.jsonError(exchange, Errors.validation(errs, requestPath)); return; }
            Map<String, Object> current = ImageObject.findById(id);
            if (current == null) { Http.jsonError(exchange, Errors.notFound(ImageObject.TYPE_NAME, requestPath)); return; }
            String ownerField = Access.ownershipField(role, "update");
            if (ownerField != null && !Objects.equals(current.get(ownerField), principal.accountId())) {
                Http.jsonError(exchange, Errors.forbidden("You may only modify your own records.", requestPath));
                return;
            }
            String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
            if (ifMatch != null && !"*".equals(ifMatch) && !ifMatch.equals(ImageObject.etagOf(current))) {
                Http.jsonError(exchange, Errors.preconditionFailed(requestPath));
                return;
            }
            String statusProp = Access.statusProperty(ENTITY);
            if (statusProp != null && body.containsKey(statusProp) && !Objects.equals(body.get(statusProp), current.get(statusProp))) {
                if (!Access.transitionAllowed(ENTITY, current.get(statusProp), body.get(statusProp), role)) {
                    Http.jsonError(exchange, Errors.forbidden("Status transition " + current.get(statusProp) + " -> " + body.get(statusProp) + " is not allowed for role \"" + role + "\".", requestPath));
                    return;
                }
            }
            // update() returns null when the record vanished between the lookup
            // above and the write (concurrent delete) — a 404, same as the lookup.
            Map<String, Object> updated = ImageObject.update(id, body);
            if (updated == null) { Http.jsonError(exchange, Errors.notFound(ImageObject.TYPE_NAME, requestPath)); return; }
            Http.json(exchange, 200, Access.stripFields(role, updated), null, ImageObject.etagOf(updated));
            return;
        }
        if ("DELETE".equals(method)) {
            if (!Access.can(role, ENTITY, "delete")) {
                Http.jsonError(exchange, Errors.forbidden("Role \"" + role + "\" may not delete " + ENTITY + ".", requestPath));
                return;
            }
            Map<String, Object> current = ImageObject.findById(id);
            if (current == null) { Http.jsonError(exchange, Errors.notFound(ImageObject.TYPE_NAME, requestPath)); return; }
            String ownerField = Access.ownershipField(role, "delete");
            if (ownerField != null && !Objects.equals(current.get(ownerField), principal.accountId())) {
                Http.jsonError(exchange, Errors.forbidden("You may only delete your own records.", requestPath));
                return;
            }
            String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
            if (ifMatch != null && !"*".equals(ifMatch) && !ifMatch.equals(ImageObject.etagOf(current))) {
                Http.jsonError(exchange, Errors.preconditionFailed(requestPath));
                return;
            }
            ImageObject.remove(id);
            Http.json(exchange, 204, null);
            return;
        }
        Http.jsonError(exchange, Errors.methodNotAllowed(List.of("GET", "PUT", "DELETE"), requestPath));
    }
}
