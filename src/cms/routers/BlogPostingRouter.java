package cms.routers;

import cms.Errors;
import cms.Http;
import cms.Router;
import cms.models.BlogPosting;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlogPostingRouter implements Router {

    public static final String BASE = "/blog-postings";
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;
    private static final Set<String> SYSTEM_FILTER_KEYS = Set.of("limit", "offset", "sort", "order");

    @Override
    public boolean dispatch(HttpExchange exchange, String method, String path, String requestPath) throws Exception {
        if (BASE.equals(path)) {
            handleCollection(exchange, method, requestPath);
            return true;
        }
        if (path.startsWith(BASE + "/")) {
            String rest = path.substring(BASE.length() + 1);
            if (rest.contains("/")) return false;
            handleItem(exchange, method, rest, requestPath);
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
            if (!BlogPosting.SORTABLE_FIELDS.contains(v)) {
                List<String> sorted = new ArrayList<>(BlogPosting.SORTABLE_FIELDS);
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
            if (!BlogPosting.SEARCHABLE_FIELDS.contains(e.getKey())) {
                opts.errors.add("Unknown filter field \"" + e.getKey() + "\".");
                continue;
            }
            opts.filter.put(e.getKey(), e.getValue().get(0));
        }
        return opts;
    }

    private void handleCollection(HttpExchange exchange, String method, String requestPath) throws IOException {
        if ("GET".equals(method)) {
            ListOptions opts = parseListOptions(exchange);
            if (!opts.errors.isEmpty()) {
                Http.jsonError(exchange, Errors.validation(opts.errors, requestPath));
                return;
            }
            Http.json(exchange, 200, BlogPosting.findAll(opts.filter, opts.sort, opts.order, opts.limit, opts.offset));
            return;
        }
        if ("POST".equals(method)) {
            Map<String, Object> body = Http.parseBody(exchange);
            List<String> errs = BlogPosting.validate(body, false);
            if (!errs.isEmpty()) {
                Http.jsonError(exchange, Errors.validation(errs, requestPath));
                return;
            }
            Map<String, Object> created = BlogPosting.create(body);
            Http.json(exchange, 201, created, Map.of("Location", BASE + "/" + created.get("id")));
            return;
        }
        Http.jsonError(exchange, Errors.methodNotAllowed(List.of("GET", "POST"), requestPath));
    }

    private void handleItem(HttpExchange exchange, String method, String id, String requestPath) throws IOException {
        if (!Http.isValidUuid(id)) {
            Http.jsonError(exchange, Errors.invalidId(requestPath));
            return;
        }
        if ("GET".equals(method)) {
            Map<String, Object> item = BlogPosting.findById(id);
            if (item == null) { Http.jsonError(exchange, Errors.notFound(BlogPosting.TYPE_NAME, requestPath)); return; }
            // Single-resource GET embeds referenced entities one level deep
            // (JSON-LD style); list responses and POST responses stay flat.
            Http.json(exchange, 200, BlogPosting.embedRefs(item));
            return;
        }
        if ("PUT".equals(method)) {
            Map<String, Object> body = Http.parseBody(exchange);
            List<String> errs = BlogPosting.validate(body, true);
            if (!errs.isEmpty()) { Http.jsonError(exchange, Errors.validation(errs, requestPath)); return; }
            Map<String, Object> current = BlogPosting.findById(id);
            if (current == null) { Http.jsonError(exchange, Errors.notFound(BlogPosting.TYPE_NAME, requestPath)); return; }
            String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
            if (ifMatch != null && !"*".equals(ifMatch) && !ifMatch.equals(BlogPosting.etagOf(current))) {
                Http.jsonError(exchange, Errors.preconditionFailed(requestPath));
                return;
            }
            Http.json(exchange, 200, BlogPosting.update(id, body));
            return;
        }
        if ("DELETE".equals(method)) {
            Map<String, Object> current = BlogPosting.findById(id);
            if (current == null) { Http.jsonError(exchange, Errors.notFound(BlogPosting.TYPE_NAME, requestPath)); return; }
            String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
            if (ifMatch != null && !"*".equals(ifMatch) && !ifMatch.equals(BlogPosting.etagOf(current))) {
                Http.jsonError(exchange, Errors.preconditionFailed(requestPath));
                return;
            }
            BlogPosting.remove(id);
            Http.json(exchange, 204, null);
            return;
        }
        Http.jsonError(exchange, Errors.methodNotAllowed(List.of("GET", "PUT", "DELETE"), requestPath));
    }
}
