package cms.models;

import cms.Storage;
import cms.Validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlogPosting {

    public static final String TYPE_NAME = "BlogPosting";
    public static final String COLLECTION_FILE = "blog-postings.json";

    public static final Map<String, Map<String, Object>> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("headline", Map.of("kind", "scalar", "type", "Text", "cardinality", "one"));
        FIELDS.put("alternativeHeadline", Map.of("kind", "scalar", "type", "Text", "cardinality", "one"));
        FIELDS.put("description", Map.of("kind", "scalar", "type", "Text", "cardinality", "one"));
        FIELDS.put("articleBody", Map.of("kind", "scalar", "type", "Text", "cardinality", "one"));
        FIELDS.put("author", Map.of("kind", "ref", "targets", List.of("Person"), "cardinality", "one"));
        FIELDS.put("image", Map.of("kind", "ref", "targets", List.of("ImageObject"), "cardinality", "many"));
        FIELDS.put("keywords", Map.of("kind", "ref", "targets", List.of("DefinedTerm"), "cardinality", "many"));
        FIELDS.put("about", Map.of("kind", "ref", "targets", List.of("CategoryCode"), "cardinality", "many"));
        FIELDS.put("datePublished", Map.of("kind", "scalar", "type", "DateTime", "cardinality", "one"));
        FIELDS.put("dateModified", Map.of("kind", "scalar", "type", "DateTime", "cardinality", "one"));
        FIELDS.put("dateCreated", Map.of("kind", "scalar", "type", "DateTime", "cardinality", "one"));
        FIELDS.put("url", Map.of("kind", "scalar", "type", "URL", "cardinality", "one"));
        FIELDS.put("inLanguage", Map.of("kind", "embed", "type", "Language", "cardinality", "one"));
        FIELDS.put("isAccessibleForFree", Map.of("kind", "scalar", "type", "Boolean", "cardinality", "one"));
        FIELDS.put("wordCount", Map.of("kind", "scalar", "type", "Integer", "cardinality", "one"));
        FIELDS.put("creativeWorkStatus", Map.of("kind", "enum", "values", List.of("Draft", "Pending", "Published", "Archived"), "cardinality", "one"));
    }

    public static final Set<String> REQUIRED_FIELDS = Set.of("headline", "articleBody", "author");
    public static final Set<String> SEARCHABLE_FIELDS = Set.of("headline", "alternativeHeadline", "description", "articleBody");
    public static final Set<String> SORTABLE_FIELDS = Set.of("dateCreated", "dateModified", "headline", "alternativeHeadline", "description", "articleBody", "datePublished", "url", "isAccessibleForFree", "wordCount", "creativeWorkStatus");

    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "dateCreated", "dateModified", "@context", "@type");

    private static final Map<String, String> REF_COLLECTIONS = new LinkedHashMap<>();
    static {
        REF_COLLECTIONS.put("Person", "persons.json");
        REF_COLLECTIONS.put("ImageObject", "image-objects.json");
        REF_COLLECTIONS.put("DefinedTerm", "defined-terms.json");
        REF_COLLECTIONS.put("CategoryCode", "category-codes.json");
    }

    private BlogPosting() {}

    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String && ((String) value).isEmpty()) return true;
        if (value instanceof List && ((List<?>) value).isEmpty()) return true;
        return false;
    }

    private static List<String> checkOne(Map<String, Object> spec, Object value, String path) {
        String kind = (String) spec.get("kind");
        switch (kind) {
            case "scalar": {
                String t = (String) spec.get("type");
                if (!Validation.checkScalar(t, value)) return List.of("Field \"" + path + "\" must be a " + t + ".");
                return List.of();
            }
            case "enum": {
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) spec.get("values");
                if (!values.contains(value)) return List.of("Field \"" + path + "\" must be one of: " + String.join(", ", values) + ".");
                return List.of();
            }
            case "ref":
                if (!Validation.isValidUuid(value)) return List.of("Field \"" + path + "\" must be a UUID.");
                return List.of();
            case "embed": {
                String t = (String) spec.get("type");
                if (!Validation.isEmbed(value, t)) return List.of("Field \"" + path + "\" must be an inline " + t + " embed with @type set.");
                return List.of();
            }
            default:
                return List.of("Field \"" + path + "\" has unknown shape.");
        }
    }

    private static List<String> checkField(Map<String, Object> spec, Object value, String name) {
        if ("many".equals(spec.get("cardinality"))) {
            if (!(value instanceof List)) return List.of("Field \"" + name + "\" must be an array.");
            List<String> errors = new ArrayList<>();
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                errors.addAll(checkOne(spec, list.get(i), name + "[" + i + "]"));
            }
            return errors;
        }
        return checkOne(spec, value, name);
    }

    public static List<String> validate(Map<String, Object> data, boolean partial) {
        if (data == null) return List.of("Request body must be a JSON object.");
        List<String> errors = new ArrayList<>();
        for (String key : data.keySet()) {
            if (Validation.isDangerousKey(key)) {
                errors.add("Unknown field \"" + key + "\".");
                continue;
            }
            if (!FIELDS.containsKey(key) && !SYSTEM_FIELDS.contains(key)) {
                errors.add("Unknown field \"" + key + "\".");
            }
        }
        if (!partial) {
            for (String field : REQUIRED_FIELDS) {
                if (isEmpty(data.get(field))) errors.add("Field \"" + field + "\" is required.");
            }
        } else {
            // A partial update may omit a required field, but must not blank one
            // that is present — that would leave the resource violating its own
            // contract.
            for (String field : REQUIRED_FIELDS) {
                if (data.containsKey(field) && isEmpty(data.get(field))) {
                    errors.add("Field \"" + field + "\" must not be empty.");
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : FIELDS.entrySet()) {
            if (!data.containsKey(entry.getKey())) continue;
            errors.addAll(checkField(entry.getValue(), data.get(entry.getKey()), entry.getKey()));
        }
        return errors;
    }

    private static String now() {
        return Instant.now().toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeRefs(Map<String, Object> data) {
        for (Map.Entry<String, Map<String, Object>> entry : FIELDS.entrySet()) {
            Map<String, Object> spec = entry.getValue();
            String name = entry.getKey();
            if (!"ref".equals(spec.get("kind")) || !data.containsKey(name)) continue;
            Object value = data.get(name);
            if ("many".equals(spec.get("cardinality")) && value instanceof List) {
                List<Object> out = new ArrayList<>();
                for (Object v : (List<?>) value) out.add(Validation.normalizeUuid(v));
                data.put(name, out);
            } else if (value instanceof String) {
                data.put(name, Validation.normalizeUuid(value));
            }
        }
        return data;
    }

    public static Map<String, Object> findAll(Map<String, Object> filter, String sort, String order, int limit, int offset) {
        List<Map<String, Object>> items = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
        if (filter != null) {
            for (Map.Entry<String, Object> e : filter.entrySet()) {
                if (!SEARCHABLE_FIELDS.contains(e.getKey())) continue;
                String needle = String.valueOf(e.getValue()).toLowerCase();
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    Object v = item.get(e.getKey());
                    if (v instanceof String && ((String) v).toLowerCase().contains(needle)) filtered.add(item);
                }
                items = filtered;
            }
        }
        String sortField = SORTABLE_FIELDS.contains(sort) ? sort : "dateCreated";
        int direction = "asc".equals(order) ? 1 : -1;
        items.sort((a, b) -> compareForSort(a.get(sortField), b.get(sortField), direction));
        int total = items.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", new ArrayList<>(items.subList(from, to)));
        result.put("total", total);
        return result;
    }

    // Type-aware ordering: numbers numerically, booleans as booleans, everything
    // else lexicographically by string form. Missing values (null) always sort
    // last, regardless of order — never coerced to "".
    private static int compareForSort(Object va, Object vb, int direction) {
        boolean aMissing = va == null;
        boolean bMissing = vb == null;
        if (aMissing || bMissing) {
            if (aMissing && bMissing) return 0;
            return aMissing ? 1 : -1;
        }
        int cmp;
        if (va instanceof Number && vb instanceof Number) {
            cmp = Double.compare(((Number) va).doubleValue(), ((Number) vb).doubleValue());
        } else if (va instanceof Boolean && vb instanceof Boolean) {
            cmp = Boolean.compare((Boolean) va, (Boolean) vb);
        } else {
            cmp = Integer.signum(va.toString().compareTo(vb.toString()));
        }
        return direction * cmp;
    }

    public static Map<String, Object> findById(String id) {
        if (!Validation.isValidUuid(id)) return null;
        String normalized = ((String) Validation.normalizeUuid(id));
        for (Map<String, Object> item : Storage.readCollection(COLLECTION_FILE)) {
            if (normalized.equals(item.get("id"))) return item;
        }
        return null;
    }

    // Embeds referenced entities one level deep for single-resource GET (JSON-LD
    // style): each ref UUID is replaced by the referenced object. List responses
    // stay flat. Embedded objects keep their own refs as UUIDs; a ref that no
    // longer resolves is left as the stored UUID string. Each target collection
    // is read at most once per request via the cache.
    @SuppressWarnings("unchecked")
    public static Map<String, Object> embedRefs(Map<String, Object> item) {
        if (REF_COLLECTIONS.isEmpty()) return item;
        // Read the target collections under the storage lock so a concurrent
        // write cannot interleave between the per-target reads — the embedded
        // view is a consistent snapshot. Each collection is read at most once.
        return Storage.withLock(() -> {
            Map<String, List<Map<String, Object>>> cache = new LinkedHashMap<>();
            Map<String, Object> out = new LinkedHashMap<>(item);
            for (Map.Entry<String, Map<String, Object>> entry : FIELDS.entrySet()) {
                Map<String, Object> spec = entry.getValue();
                if (!"ref".equals(spec.get("kind"))) continue;
                String name = entry.getKey();
                Object value = out.get(name);
                if (value == null) continue;
                List<String> targets = (List<String>) spec.get("targets");
                if ("many".equals(spec.get("cardinality"))) {
                    if (!(value instanceof List)) continue;
                    List<Object> resolved = new ArrayList<>();
                    for (Object element : (List<?>) value) resolved.add(resolveRef(element, targets, cache));
                    out.put(name, resolved);
                } else {
                    out.put(name, resolveRef(value, targets, cache));
                }
            }
            return out;
        });
    }

    private static Object resolveRef(Object id, List<String> targets, Map<String, List<Map<String, Object>>> cache) {
        if (!(id instanceof String)) return id;
        for (String target : targets) {
            String file = REF_COLLECTIONS.get(target);
            if (file == null) continue;
            List<Map<String, Object>> collection = cache.computeIfAbsent(file, Storage::readCollection);
            for (Map<String, Object> entry : collection) {
                if (id.equals(entry.get("id"))) return entry;
            }
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> create(Map<String, Object> rawData) {
        return Storage.withLock(() -> {
            Map<String, Object> data = normalizeRefs((Map<String, Object>) Validation.deepSanitize(rawData));
            List<Map<String, Object>> items = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            String n = now();
            Map<String, Object> item = new LinkedHashMap<>();
            item.putAll(data);
            // System-controlled fields are written last so a client cannot spoof
            // @context, @type, id or the timestamps by sending them in the body.
            item.put("@context", "https://schema.org");
            item.put("@type", TYPE_NAME);
            item.put("id", Validation.generateUuid());
            item.put("dateCreated", n);
            item.put("dateModified", n);
            items.add(item);
            Storage.writeCollection(COLLECTION_FILE, items);
            return item;
        });
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> update(String id, Map<String, Object> rawData) {
        return Storage.withLock(() -> {
            List<Map<String, Object>> items = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            String normalized = ((String) Validation.normalizeUuid(id));
            int idx = -1;
            for (int i = 0; i < items.size(); i++) {
                if (normalized.equals(items.get(i).get("id"))) { idx = i; break; }
            }
            if (idx < 0) return null;
            Map<String, Object> current = items.get(idx);
            Map<String, Object> data = normalizeRefs((Map<String, Object>) Validation.deepSanitize(rawData));
            Map<String, Object> updated = new LinkedHashMap<>(current);
            updated.putAll(data);
            updated.put("@context", current.getOrDefault("@context", "https://schema.org"));
            updated.put("@type", current.getOrDefault("@type", TYPE_NAME));
            updated.put("id", current.get("id"));
            updated.put("dateCreated", current.get("dateCreated"));
            updated.put("dateModified", now());
            items.set(idx, updated);
            Storage.writeCollection(COLLECTION_FILE, items);
            return updated;
        });
    }

    public static boolean remove(String id) {
        return Storage.withLock(() -> {
            List<Map<String, Object>> items = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            String normalized = ((String) Validation.normalizeUuid(id));
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> item : items) {
                if (!normalized.equals(item.get("id"))) filtered.add(item);
            }
            if (filtered.size() == items.size()) return false;
            Storage.writeCollection(COLLECTION_FILE, filtered);
            return true;
        });
    }

    public static String etagOf(Map<String, Object> item) {
        return Validation.etagFor(item);
    }
}
