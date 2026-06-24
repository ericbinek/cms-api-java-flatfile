package cms.models;

import cms.Storage;
import cms.Validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CategoryCodeSet {

    public static final String TYPE_NAME = "CategoryCodeSet";
    public static final String COLLECTION_FILE = "category-code-sets.json";

    public static final Map<String, FieldSpec> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("name", new FieldSpec.Scalar("Text", FieldSpec.Cardinality.ONE, 256, false));
        FIELDS.put("description", new FieldSpec.Scalar("Text", FieldSpec.Cardinality.ONE, 5000, true));
        FIELDS.put("url", new FieldSpec.Scalar("URL", FieldSpec.Cardinality.ONE, 2048, false));
    }

    public static final Set<String> REQUIRED_FIELDS = Set.of("name");
    public static final Set<String> SEARCHABLE_FIELDS = Set.of("name", "description");
    public static final Set<String> SORTABLE_FIELDS = Set.of("dateCreated", "dateModified", "name", "description", "url");

    // Properties whose combined value must be unique across the collection.
    // Empty when the entity allows duplicates.
    public static final List<String> UNIQUE_KEY = List.of();

    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "dateCreated", "dateModified", "@context", "@type");

    private static final Map<String, String> REF_COLLECTIONS = new LinkedHashMap<>();
    static {

    }

    private CategoryCodeSet() {}

    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String && ((String) value).isEmpty()) return true;
        if (value instanceof List && ((List<?>) value).isEmpty()) return true;
        return false;
    }

    private static List<String> checkOne(FieldSpec spec, Object value, String path) {
        return switch (spec) {
            case FieldSpec.Scalar s -> {
                if (!Validation.checkScalar(s.type(), value)) {
                    yield List.of("Field \"" + path + "\" must be a " + s.type() + ".");
                }
                if (s.maxLength() != null && value instanceof String str && str.length() > s.maxLength()) {
                    yield List.of("Field \"" + path + "\" must be at most " + s.maxLength() + " characters.");
                }
                yield List.of();
            }
            case FieldSpec.Enumerated e -> e.values().contains(value)
                ? List.of()
                : List.of("Field \"" + path + "\" must be one of: " + String.join(", ", e.values()) + ".");
            case FieldSpec.Ref ignored -> Validation.isValidUuid(value)
                ? List.of()
                : List.of("Field \"" + path + "\" must be a UUID.");
            case FieldSpec.Embed em -> Validation.isEmbed(value, em.type())
                ? List.of()
                : List.of("Field \"" + path + "\" must be an inline " + em.type() + " embed with @type set.");
        };
    }

    private static List<String> checkField(FieldSpec spec, Object value, String name) {
        if (spec.cardinality() == FieldSpec.Cardinality.MANY) {
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
        for (Map.Entry<String, FieldSpec> entry : FIELDS.entrySet()) {
            if (!data.containsKey(entry.getKey())) continue;
            errors.addAll(checkField(entry.getValue(), data.get(entry.getKey()), entry.getKey()));
        }
        return errors;
    }

    private static String now() {
        return Instant.now().toString();
    }

    // Field-aware input cleaning, run before validation and storage: each known
    // scalar string is normalized, stripped of control characters and trimmed,
    // with long-form (multiline) fields keeping their internal line breaks. Refs,
    // embeds, lists and other values fall back to the conservative property-blind
    // sanitizer. The body is cleaned in place: every key is left where it is —
    // dangerous keys (__proto__, …) are deliberately untouched so validate() can
    // reject the body, rather than silently dropped here.
    public static Map<String, Object> sanitize(Map<String, Object> data) {
        for (String key : new ArrayList<>(data.keySet())) {
            if (Validation.isDangerousKey(key)) continue;
            Object value = data.get(key);
            FieldSpec spec = FIELDS.get(key);
            if (spec instanceof FieldSpec.Scalar scalar && value instanceof String str) {
                data.put(key, Validation.sanitizeString(str, scalar.multiline()));
            } else {
                data.put(key, Validation.deepSanitize(value));
            }
        }
        return data;
    }

    private static Map<String, Object> normalizeRefs(Map<String, Object> data) {
        for (Map.Entry<String, FieldSpec> entry : FIELDS.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof FieldSpec.Ref ref) || !data.containsKey(name)) continue;
            Object value = data.get(name);
            if (ref.cardinality() == FieldSpec.Cardinality.MANY && value instanceof List<?> list) {
                List<Object> out = new ArrayList<>();
                for (Object v : list) out.add(Validation.normalizeUuid(v));
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
    public static Map<String, Object> embedRefs(Map<String, Object> item) {
        if (REF_COLLECTIONS.isEmpty()) return item;
        // Read the target collections under the storage lock so a concurrent
        // write cannot interleave between the per-target reads — the embedded
        // view is a consistent snapshot. Each collection is read at most once.
        return Storage.withLock(() -> {
            Map<String, List<Map<String, Object>>> cache = new LinkedHashMap<>();
            Map<String, Object> out = new LinkedHashMap<>(item);
            for (Map.Entry<String, FieldSpec> entry : FIELDS.entrySet()) {
                if (!(entry.getValue() instanceof FieldSpec.Ref ref)) continue;
                String name = entry.getKey();
                Object value = out.get(name);
                if (value == null) continue;
                List<String> targets = ref.targets();
                if (ref.cardinality() == FieldSpec.Cardinality.MANY) {
                    if (!(value instanceof List<?> list)) continue;
                    List<Object> resolved = new ArrayList<>();
                    for (Object element : list) resolved.add(resolveRef(element, targets, cache));
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

    // A candidate collides when some other record shares every unique-key value.
    // Comparison runs on already-sanitized, ref-normalized data, so equal values
    // are in canonical form. Entities without a key never collide.
    private static boolean violatesUniqueKey(List<Map<String, Object>> items, Map<String, Object> candidate, String excludeId) {
        if (UNIQUE_KEY.isEmpty()) return false;
        for (Map<String, Object> item : items) {
            if (Objects.equals(item.get("id"), excludeId)) continue;
            boolean match = true;
            for (String field : UNIQUE_KEY) {
                if (!Objects.equals(item.get(field), candidate.get(field))) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    private static DuplicateException duplicateError() {
        String fields = String.join(" and ", UNIQUE_KEY);
        return new DuplicateException(List.of("A " + TYPE_NAME + " with this " + fields + " already exists."));
    }

    public static Map<String, Object> create(Map<String, Object> rawData) {
        return Storage.withLock(() -> {
            Map<String, Object> data = normalizeRefs(rawData);
            List<Map<String, Object>> items = new ArrayList<>(Storage.readCollection(COLLECTION_FILE));
            if (violatesUniqueKey(items, data, null)) throw duplicateError();
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
            Map<String, Object> data = normalizeRefs(rawData);
            Map<String, Object> updated = new LinkedHashMap<>(current);
            updated.putAll(data);
            updated.put("@context", current.getOrDefault("@context", "https://schema.org"));
            updated.put("@type", current.getOrDefault("@type", TYPE_NAME));
            updated.put("id", current.get("id"));
            updated.put("dateCreated", current.get("dateCreated"));
            updated.put("dateModified", now());
            if (violatesUniqueKey(items, updated, (String) current.get("id"))) throw duplicateError();
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
