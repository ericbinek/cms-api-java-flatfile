package cms.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Auth conformance suite: the scenarios in docs/auth/conformance.md as executable
// spec. Generated against this target's compiled access policy. Each run uses an
// isolated server seeded with the five accounts that cover the matrix, ownership
// and the workflow roles; the bootstrap scenarios spin up their own servers.
public final class AuthConformanceTest {

    private AuthConformanceTest() {}

    private static Map<String, Object> creds(String username, String password) {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("username", username);
        creds.put("password", password);
        return creds;
    }

    // Create through the public API as `bearer`. The payload (and any ref deps) is
    // built as admin via the static token; the create itself runs as `bearer`.
    private static Helpers.Response createAs(String base, String adminToken, String bearer, String entity, String entityBase) {
        Helpers.setAuthToken(adminToken);
        Map<String, Object> payload = Helpers.buildPayload(entity, false);
        return Helpers.requestTo(base, bearer, "POST", entityBase, payload);
    }

    private static boolean containsId(List<Object> items, String id) {
        for (Object o : items) {
            if (o instanceof Map && id.equals(((Map<?, ?>) o).get("id"))) return true;
        }
        return false;
    }

    public static void run(TestRunner.TestContext ctx) {
        List<Map<String, Object>> accounts = new ArrayList<>();
        accounts.add(TestServer.account("admin",   "pw-admin",   "admin"));
        accounts.add(TestServer.account("editor",  "pw-editor",  "editor"));
        accounts.add(TestServer.account("author",  "pw-author",  "author"));
        accounts.add(TestServer.account("author2", "pw-author2", "author"));
        accounts.add(TestServer.account("viewer",  "pw-viewer",  "viewer"));

        try (TestServer server = TestServer.start(accounts, null)) {
            final String base = server.baseUrl;
            final Map<String, String> token = new LinkedHashMap<>();
            token.put("admin",   Helpers.login(base, "admin",   "pw-admin"));
            token.put("editor",  Helpers.login(base, "editor",  "pw-editor"));
            token.put("author",  Helpers.login(base, "author",  "pw-author"));
            token.put("author2", Helpers.login(base, "author2", "pw-author2"));
            token.put("viewer",  Helpers.login(base, "viewer",  "pw-viewer"));

            // buildPayload/makeDep build through the public API as admin against this server.
            Helpers.setBase(base);
            Helpers.setAuthToken(token.get("admin"));

            // --- Authentication ---

            ctx.test("login with valid credentials returns token, account and expiresAt", () -> {
                Helpers.Response r = Helpers.requestTo(base, null, "POST", "/auth/login", creds("admin", "pw-admin"));
                Assert.equal(200, r.status);
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
                Assert.isTrue(body.get("token") instanceof String, "token is a string");
                @SuppressWarnings("unchecked") Map<String, Object> account = (Map<String, Object>) body.get("account");
                Assert.equal("admin", account.get("username"));
                Assert.equal("admin", account.get("role"));
                Assert.isTrue(account.get("id") != null, "account id present");
                Assert.isTrue(body.get("expiresAt") != null, "expiresAt present");
                Assert.isTrue(!account.containsKey("passwordHash"), "no passwordHash leaked");
            });

            ctx.test("login with wrong password returns 401 UNAUTHORIZED", () -> {
                Helpers.Response r = Helpers.requestTo(base, null, "POST", "/auth/login", creds("admin", "wrong"));
                Assert.equal(401, r.status);
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
                Assert.equal("UNAUTHORIZED", body.get("error"));
            });

            ctx.test("login with unknown user returns the same 401 (no enumeration)", () -> {
                Helpers.Response r = Helpers.requestTo(base, null, "POST", "/auth/login", creds("ghost", "whatever"));
                Assert.equal(401, r.status);
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
                Assert.equal("UNAUTHORIZED", body.get("error"));
            });

            ctx.test("login with missing fields returns 400 VALIDATION_ERROR", () -> {
                Map<String, Object> partial = new LinkedHashMap<>();
                partial.put("username", "admin");
                Helpers.Response r = Helpers.requestTo(base, null, "POST", "/auth/login", partial);
                Assert.equal(400, r.status);
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
                Assert.equal("VALIDATION_ERROR", body.get("error"));
            });

            ctx.test("GET /auth/me with a valid token returns the account, never internals", () -> {
                Helpers.Response r = Helpers.requestTo(base, token.get("author"), "GET", "/auth/me", null);
                Assert.equal(200, r.status);
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
                @SuppressWarnings("unchecked") Map<String, Object> account = (Map<String, Object>) body.get("account");
                Assert.equal("author", account.get("username"));
                Assert.equal("author", account.get("role"));
                Assert.isTrue(!account.containsKey("passwordHash"), "no passwordHash leaked");
            });

            ctx.test("GET /auth/me without a token returns 401", () -> {
                Helpers.Response r = Helpers.requestTo(base, null, "GET", "/auth/me", null);
                Assert.equal(401, r.status);
            });

            ctx.test("GET /auth/me with an invalid token returns 401", () -> {
                Helpers.Response r = Helpers.requestTo(base, "not-a-real-token", "GET", "/auth/me", null);
                Assert.equal(401, r.status);
            });

            ctx.test("logout invalidates the session immediately; reuse and re-logout are 401", () -> {
                String fresh = Helpers.login(base, "viewer", "pw-viewer");
                Helpers.Response out = Helpers.requestTo(base, fresh, "POST", "/auth/logout", null);
                Assert.equal(204, out.status);
                Helpers.Response reuse = Helpers.requestTo(base, fresh, "GET", "/auth/me", null);
                Assert.equal(401, reuse.status);
                Helpers.Response again = Helpers.requestTo(base, fresh, "POST", "/auth/logout", null);
                Assert.equal(401, again.status);
            });

            ctx.test("logout without a token returns 401", () -> {
                Helpers.Response r = Helpers.requestTo(base, null, "POST", "/auth/logout", null);
                Assert.equal(401, r.status);
            });

            final String WF = "BlogPosting";
            final String WB = "/blog-postings";
            final String SP = "creativeWorkStatus";
            final String INITIAL = "Draft";
            final String AUTHOR_TO = "Pending";
            final String EDITOR_TO = "Published";
            final String PUBLIC_STATUS = "Published";

            // --- Authorization (type-level) ---

            ctx.test("write without a session returns 401 (middleware), not 403", () -> {
                Helpers.setAuthToken(token.get("admin"));
                Map<String, Object> payload = Helpers.buildPayload(WF, false);
                Helpers.Response r = Helpers.requestTo(base, null, "POST", WB, payload);
                Assert.equal(401, r.status);
            });

            ctx.test("viewer may read but not create, update or delete", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) createAs(base, token.get("admin"), token.get("admin"), WF, WB).body;
                Assert.equal(200, Helpers.requestTo(base, token.get("viewer"), "GET", WB + "/" + item.get("id"), null).status);
                Assert.equal(403, createAs(base, token.get("admin"), token.get("viewer"), WF, WB).status);
                Assert.equal(403, Helpers.requestTo(base, token.get("viewer"), "PUT", WB + "/" + item.get("id"), new LinkedHashMap<>()).status);
                Assert.equal(403, Helpers.requestTo(base, token.get("viewer"), "DELETE", WB + "/" + item.get("id"), null).status);
            });

            ctx.test("author may read and create; editor and admin have full CRUD", () -> {
                Assert.equal(201, createAs(base, token.get("admin"), token.get("author"), WF, WB).status);
                Assert.equal(201, createAs(base, token.get("admin"), token.get("editor"), WF, WB).status);
                Assert.equal(201, createAs(base, token.get("admin"), token.get("admin"), WF, WB).status);
            });

            // --- Ownership ---

            ctx.test("createdBy is set to the creator and an author may modify only own records", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> mine = (Map<String, Object>) createAs(base, token.get("admin"), token.get("author"), WF, WB).body;
                @SuppressWarnings("unchecked") Map<String, Object> theirs = (Map<String, Object>) createAs(base, token.get("admin"), token.get("author2"), WF, WB).body;

                Assert.equal(200, Helpers.requestTo(base, token.get("author"), "PUT", WB + "/" + mine.get("id"), new LinkedHashMap<>()).status);
                Assert.equal(403, Helpers.requestTo(base, token.get("author"), "PUT", WB + "/" + theirs.get("id"), new LinkedHashMap<>()).status);
                Assert.equal(403, Helpers.requestTo(base, token.get("author"), "DELETE", WB + "/" + theirs.get("id"), null).status);

                Assert.equal(200, Helpers.requestTo(base, token.get("editor"), "PUT", WB + "/" + theirs.get("id"), new LinkedHashMap<>()).status);
                Assert.equal(204, Helpers.requestTo(base, token.get("admin"), "DELETE", WB + "/" + mine.get("id"), null).status);
            });

            // --- Field-level ---

            ctx.test("createdBy never appears in any entity response", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) createAs(base, token.get("admin"), token.get("admin"), WF, WB).body;
                Assert.isTrue(!created.containsKey("createdBy"), "createdBy absent from create response");
                @SuppressWarnings("unchecked") Map<String, Object> got = (Map<String, Object>) Helpers.requestTo(base, token.get("admin"), "GET", WB + "/" + created.get("id"), null).body;
                Assert.isTrue(!got.containsKey("createdBy"), "createdBy absent from get response");
                @SuppressWarnings("unchecked") Map<String, Object> listBody = (Map<String, Object>) Helpers.requestTo(base, token.get("admin"), "GET", WB + "?limit=100", null).body;
                @SuppressWarnings("unchecked") List<Object> items = (List<Object>) listBody.get("items");
                for (Object o : items) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                    Assert.isTrue(!m.containsKey("createdBy"), "createdBy absent from list item");
                }
            });

            ctx.test("system and internal fields are rejected in a write body with 400", () -> {
                Helpers.setAuthToken(token.get("admin"));
                for (String field : new String[]{"id", "dateCreated", "dateModified", "createdBy"}) {
                    Map<String, Object> payload = Helpers.buildPayload(WF, false);
                    payload.put(field, "id".equals(field) ? "00000000-0000-0000-0000-000000000000" : "x");
                    Helpers.Response r = Helpers.requestTo(base, token.get("admin"), "POST", WB, payload);
                    Assert.equal(400, r.status, "expected 400 for field " + field + ", got " + r.status);
                    @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.body;
                    Assert.equal("VALIDATION_ERROR", body.get("error"));
                }
            });

            ctx.test("server-managed fields appear in output but are server set", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) createAs(base, token.get("admin"), token.get("admin"), WF, WB).body;
                Assert.isTrue(created.get("id") != null, "id present");
                Assert.isTrue(created.get("dateCreated") != null, "dateCreated present");
                Assert.isTrue(created.get("dateModified") != null, "dateModified present");
            });

            // --- Publication workflow ---

            ctx.test("a freshly created BlogPosting has the initial status", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) createAs(base, token.get("admin"), token.get("author"), WF, WB).body;
                Assert.equal(INITIAL, created.get(SP));
            });

            ctx.test("author may run the initial transition but not the editor-only one", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) createAs(base, token.get("admin"), token.get("author"), WF, WB).body;
                Map<String, Object> toAuthor = new LinkedHashMap<>();
                toAuthor.put(SP, AUTHOR_TO);
                Helpers.Response a = Helpers.requestTo(base, token.get("author"), "PUT", WB + "/" + item.get("id"), toAuthor);
                Assert.equal(200, a.status);
                @SuppressWarnings("unchecked") Map<String, Object> aBody = (Map<String, Object>) a.body;
                Assert.equal(AUTHOR_TO, aBody.get(SP));
                Map<String, Object> toEditor = new LinkedHashMap<>();
                toEditor.put(SP, EDITOR_TO);
                Assert.equal(403, Helpers.requestTo(base, token.get("author"), "PUT", WB + "/" + item.get("id"), toEditor).status);
                Assert.equal(200, Helpers.requestTo(base, token.get("editor"), "PUT", WB + "/" + item.get("id"), toEditor).status);
            });

            ctx.test("an unmodelled transition is forbidden", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) createAs(base, token.get("admin"), token.get("editor"), WF, WB).body;
                Map<String, Object> toEditor = new LinkedHashMap<>();
                toEditor.put(SP, EDITOR_TO);
                Assert.equal(403, Helpers.requestTo(base, token.get("editor"), "PUT", WB + "/" + item.get("id"), toEditor).status);
            });

            // --- Anonymous visibility (public) ---

            ctx.test("anonymous reads see only public records; non-public detail is 404", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) createAs(base, token.get("admin"), token.get("admin"), WF, WB).body;
                String id = (String) item.get("id");

                @SuppressWarnings("unchecked") Map<String, Object> hiddenList = (Map<String, Object>) Helpers.requestTo(base, null, "GET", WB + "?limit=100", null).body;
                @SuppressWarnings("unchecked") List<Object> hiddenItems = (List<Object>) hiddenList.get("items");
                Assert.isTrue(!containsId(hiddenItems, id), "draft hidden from anonymous list");
                Assert.equal(404, Helpers.requestTo(base, null, "GET", WB + "/" + id, null).status);

                // Drive it to the public status (admin), reaching datePublished where required.
                Map<String, Object> toAuthor = new LinkedHashMap<>();
                toAuthor.put(SP, AUTHOR_TO);
                Helpers.requestTo(base, token.get("admin"), "PUT", WB + "/" + id, toAuthor);
                Map<String, Object> publish = new LinkedHashMap<>();
                publish.put(SP, PUBLIC_STATUS);
                publish.put("datePublished", "2020-01-01T00:00:00Z");
                Helpers.Response pub = Helpers.requestTo(base, token.get("admin"), "PUT", WB + "/" + id, publish);
                Assert.equal(200, pub.status);

                @SuppressWarnings("unchecked") Map<String, Object> shownList = (Map<String, Object>) Helpers.requestTo(base, null, "GET", WB + "?limit=100", null).body;
                @SuppressWarnings("unchecked") List<Object> shownItems = (List<Object>) shownList.get("items");
                Assert.isTrue(containsId(shownItems, id), "published visible in anonymous list");
                Helpers.Response detail = Helpers.requestTo(base, null, "GET", WB + "/" + id, null);
                Assert.equal(200, detail.status);
                @SuppressWarnings("unchecked") Map<String, Object> detailBody = (Map<String, Object>) detail.body;
                Assert.isTrue(!detailBody.containsKey("createdBy"), "createdBy absent from anonymous detail");
            });

            ctx.test("an entity without a status enum is anonymously readable and unrestricted by workflow", () -> {
                @SuppressWarnings("unchecked") Map<String, Object> created = (Map<String, Object>) createAs(base, token.get("admin"), token.get("admin"), "Person", "/persons").body;
                Helpers.Response anon = Helpers.requestTo(base, null, "GET", "/persons" + "/" + created.get("id"), null);
                Assert.equal(200, anon.status);
                Helpers.Response upd = Helpers.requestTo(base, token.get("editor"), "PUT", "/persons" + "/" + created.get("id"), new LinkedHashMap<>());
                Assert.equal(200, upd.status);
            });

            // --- Bootstrap ---

            ctx.test("empty store plus ADMIN env seeds one admin that can log in", () -> {
                Map<String, String> bootEnv = new LinkedHashMap<>();
                bootEnv.put("ADMIN_USER", "root");
                bootEnv.put("ADMIN_PASSWORD", "root-pw");
                try (TestServer s = TestServer.start(null, bootEnv)) {
                    Assert.isTrue(Helpers.login(s.baseUrl, "root", "root-pw") != null, "token issued");
                }
            });

            ctx.test("a non-empty store makes the env seed a no-op", () -> {
                Map<String, String> bootEnv = new LinkedHashMap<>();
                bootEnv.put("ADMIN_USER", "ghost");
                bootEnv.put("ADMIN_PASSWORD", "ghost-pw");
                try (TestServer s = TestServer.start(accounts, bootEnv)) {
                    Helpers.Response r = Helpers.requestTo(s.baseUrl, null, "POST", "/auth/login", creds("ghost", "ghost-pw"));
                    Assert.equal(401, r.status);
                }
            });

            ctx.test("empty store without env grants no one: protected writes are 401", () -> {
                try (TestServer s = TestServer.start(new ArrayList<>(), null)) {
                    Helpers.setAuthToken(token.get("admin"));
                    Map<String, Object> payload = Helpers.buildPayload(WF, false);
                    Helpers.Response r = Helpers.requestTo(s.baseUrl, null, "POST", WB, payload);
                    Assert.equal(401, r.status);
                }
            });
        }
    }
}
