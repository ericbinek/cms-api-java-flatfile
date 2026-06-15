package cms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Compiled access policy for this target, derived from the project-wide access/
 * authority (roles.json, field-access.json, workflow.json). Pure data plus pure
 * helpers — no IO, no request handling. The policy is embedded as JSON and parsed
 * once at class load; the router and server enforce it through the typed helpers.
 */
public final class Access {

    private static final String POLICY_JSON = """
        {
          "operations": [
            "read",
            "create",
            "update",
            "delete"
          ],
          "roles": {
            "admin": {
              "matrix": {
                "*": [
                  "read",
                  "create",
                  "update",
                  "delete"
                ]
              },
              "accountManagement": true
            },
            "editor": {
              "matrix": {
                "*": [
                  "read",
                  "create",
                  "update",
                  "delete"
                ]
              }
            },
            "author": {
              "matrix": {
                "*": [
                  "read",
                  "create",
                  "update",
                  "delete"
                ]
              },
              "ownership": {
                "scope": "own",
                "operations": [
                  "update",
                  "delete"
                ],
                "field": "createdBy"
              }
            },
            "viewer": {
              "matrix": {
                "*": [
                  "read"
                ]
              }
            },
            "anonymous": {
              "matrix": {
                "*": [
                  "read"
                ]
              },
              "read": {
                "visibility": "public"
              }
            }
          },
          "visibility": {
            "scopes": [
              "all",
              "public"
            ]
          },
          "fieldGroups": {
            "system": [
              "id",
              "dateCreated",
              "dateModified"
            ],
            "internal": [
              "createdBy"
            ]
          },
          "fieldRules": {
            "*": {
              "read": {
                "deny": [
                  "@internal"
                ]
              },
              "write": {
                "deny": [
                  "@system",
                  "@internal"
                ]
              }
            }
          },
          "workflow": {
            "BlogPosting": {
              "statusProperty": "creativeWorkStatus",
              "initial": "Draft",
              "public": [
                "Published"
              ],
              "transitions": [
                {
                  "from": "Draft",
                  "to": "Pending",
                  "roles": [
                    "author",
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Pending",
                  "to": "Draft",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Pending",
                  "to": "Published",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Published",
                  "to": "Archived",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Archived",
                  "to": "Published",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                }
              ],
              "hasPublishDate": true
            },
            "WebPage": {
              "statusProperty": "creativeWorkStatus",
              "initial": "Draft",
              "public": [
                "Published"
              ],
              "transitions": [
                {
                  "from": "Draft",
                  "to": "Pending",
                  "roles": [
                    "author",
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Pending",
                  "to": "Draft",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Pending",
                  "to": "Published",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Published",
                  "to": "Archived",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Archived",
                  "to": "Published",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                }
              ],
              "hasPublishDate": true
            },
            "Comment": {
              "statusProperty": "creativeWorkStatus",
              "initial": "Pending",
              "public": [
                "Approved"
              ],
              "transitions": [
                {
                  "from": "Pending",
                  "to": "Approved",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Pending",
                  "to": "Spam",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Approved",
                  "to": "Spam",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Approved",
                  "to": "Trash",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                },
                {
                  "from": "Spam",
                  "to": "Trash",
                  "roles": [
                    "editor",
                    "admin"
                  ]
                }
              ],
              "hasPublishDate": false
            }
          }
        }
        """;

    @SuppressWarnings("unchecked")
    private static final Map<String, Object> POLICY = (Map<String, Object>) Json.parse(POLICY_JSON);

    private Access() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static Map<String, Object> role(String role) {
        return map(map(POLICY.get("roles")).get(role));
    }

    private static Map<String, Object> workflow(String entity) {
        Map<String, Object> wf = map(POLICY.get("workflow"));
        return wf == null ? null : map(wf.get(entity));
    }

    // Resolves a role's field rule for a mode (read/write) into a concrete deny set,
    // expanding the group references @system and @internal. A per-role rule wins over
    // the "*" default. "deny" wins; an absent rule denies nothing.
    private static Set<String> denySet(String role, String mode) {
        Map<String, Object> rules = map(POLICY.get("fieldRules"));
        Map<String, Object> byRole = map(rules.get(role));
        Map<String, Object> rule = byRole != null ? map(byRole.get(mode)) : null;
        if (rule == null) {
            Map<String, Object> byDefault = map(rules.get("*"));
            rule = byDefault != null ? map(byDefault.get(mode)) : null;
        }
        Set<String> deny = new LinkedHashSet<>();
        if (rule == null) return deny;
        Map<String, Object> groups = map(POLICY.get("fieldGroups"));
        for (Object entry : list(rule.get("deny"))) {
            if ("@system".equals(entry)) {
                for (Object f : list(groups.get("system"))) deny.add((String) f);
            } else if ("@internal".equals(entry)) {
                for (Object f : list(groups.get("internal"))) deny.add((String) f);
            } else if (entry instanceof String) {
                deny.add((String) entry);
            }
        }
        return deny;
    }

    // The fields no client may ever write (system + internal), i.e. the default
    // write deny resolved. Exposed for request builders and tests.
    public static Set<String> readonlyFields() {
        return denySet("*", "write");
    }

    // Type-level: may `role` perform `op` on `entity`? A per-entity matrix entry
    // overrides the "*" default for that entity only.
    public static boolean can(String role, String entity, String op) {
        Map<String, Object> r = role(role);
        if (r == null) return false;
        Map<String, Object> matrix = map(r.get("matrix"));
        if (matrix == null) return false;
        Object ops = matrix.containsKey(entity) ? matrix.get(entity) : matrix.get("*");
        return ops instanceof List && ((List<?>) ops).contains(op);
    }

    // Ownership: the owner field name if `role` is restricted to its own records for
    // `op` (e.g. author update/delete -> "createdBy"), else null.
    public static String ownershipField(String role, String op) {
        Map<String, Object> r = role(role);
        if (r == null) return null;
        Map<String, Object> own = map(r.get("ownership"));
        if (own == null) return null;
        if (!list(own.get("operations")).contains(op)) return null;
        return (String) own.get("field");
    }

    public static boolean isGoverned(String entity) {
        return workflow(entity) != null;
    }

    public static String statusProperty(String entity) {
        Map<String, Object> machine = workflow(entity);
        return machine == null ? null : (String) machine.get("statusProperty");
    }

    public static String initialStatus(String entity) {
        Map<String, Object> machine = workflow(entity);
        return machine == null ? null : (String) machine.get("initial");
    }

    // May `role` move `entity` from `from` to `to`? Non-governed entities and no-op
    // transitions (from equals to) are always allowed; everything else must be modelled.
    public static boolean transitionAllowed(String entity, Object from, Object to, String role) {
        Map<String, Object> machine = workflow(entity);
        if (machine == null) return true;
        if (Objects.equals(from, to)) return true;
        for (Object t : list(machine.get("transitions"))) {
            Map<String, Object> transition = map(t);
            if (transition != null
                && Objects.equals(transition.get("from"), from)
                && Objects.equals(transition.get("to"), to)
                && list(transition.get("roles")).contains(role)) {
                return true;
            }
        }
        return false;
    }

    // Field-level write: the names in `body` a `role` is not allowed to set (system
    // and internal fields). Any hit is a 400, not a silent drop.
    public static List<String> readonlyViolations(String role, Object body) {
        if (!(body instanceof Map)) return List.of();
        Set<String> deny = denySet(role, "write");
        List<String> hits = new ArrayList<>();
        for (Object key : ((Map<?, ?>) body).keySet()) {
            if (key instanceof String && deny.contains(key)) hits.add((String) key);
        }
        return hits;
    }

    // Field-level read: strip denied (internal) fields from a value before it leaves
    // the server, recursing into arrays and embedded objects so embeds are covered.
    public static Object stripFields(String role, Object value) {
        return walkStrip(value, denySet(role, "read"));
    }

    @SuppressWarnings("unchecked")
    private static Object walkStrip(Object value, Set<String> deny) {
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object element : (List<Object>) value) out.add(walkStrip(element, deny));
            return out;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (deny.contains(entry.getKey())) continue;
                out.put(entry.getKey(), walkStrip(entry.getValue(), deny));
            }
            return out;
        }
        return value;
    }

    // On create the server stamps ownership (createdBy) and forces the workflow entry
    // state, overriding any client-supplied status.
    public static Map<String, Object> applyCreateDefaults(String entity, Map<String, Object> data, String accountId) {
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("createdBy", accountId);
        String initial = initialStatus(entity);
        if (initial != null) out.put(statusProperty(entity), initial);
        return out;
    }

    // Anonymous read visibility: "public" gates status-bearing entities to their
    // public states (and a reached datePublished where the entity has one); "all"
    // returns every record. Internal fields are stripped under either scope.
    private static String readVisibility(String role) {
        Map<String, Object> r = role(role);
        if (r == null) return "all";
        Map<String, Object> read = map(r.get("read"));
        if (read == null) return "all";
        Object visibility = read.get("visibility");
        return visibility instanceof String ? (String) visibility : "all";
    }

    public static boolean isVisible(String role, String entity, Map<String, Object> item) {
        if (!"public".equals(readVisibility(role))) return true;
        Map<String, Object> wf = workflow(entity);
        if (wf == null) return true;
        Object status = item.get((String) wf.get("statusProperty"));
        if (!list(wf.get("public")).contains(status)) return false;
        if (Boolean.TRUE.equals(wf.get("hasPublishDate"))) {
            Object published = item.get("datePublished");
            if (!(published instanceof String)) return false;
            Instant at = parseInstant((String) published);
            if (at == null || at.isAfter(Instant.now())) return false;
        }
        return true;
    }

    private static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
