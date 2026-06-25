package cms;

import cms.models.Account;
import cms.routers.AuthRouter;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import cms.routers.BlogPostingRouter;
import cms.routers.PersonRouter;
import cms.routers.OrganizationRouter;
import cms.routers.WebPageRouter;
import cms.routers.ImageObjectRouter;
import cms.routers.VideoObjectRouter;
import cms.routers.AudioObjectRouter;
import cms.routers.CategoryCodeRouter;
import cms.routers.CategoryCodeSetRouter;
import cms.routers.DefinedTermRouter;
import cms.routers.DefinedTermSetRouter;
import cms.routers.CommentRouter;
import cms.routers.WebSiteRouter;
import cms.routers.SiteNavigationElementRouter;

public final class Server {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3006"));
        String host = System.getenv().getOrDefault("HOST", "0.0.0.0");
        // Bootstrap the first admin (if configured) before accepting requests.
        Account.seedAdmin();
        HttpServer server = create(host, port);
        server.start();
        System.err.println("CMS API running at http://" + host + ":" + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutdown requested. Stopping...");
            server.stop(2);
            System.err.println("Server closed.");
        }));
    }

    public static HttpServer create(String host, int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        register(server, "/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
                return;
            }
            Http.json(exchange, 200, java.util.Map.of("status", "ok"));
        }, null);
        register(server, "/auth", new AuthRouter(), Auth.filter(false));
        register(server, "/blog-postings", new BlogPostingRouter(), Auth.filter(true));
        register(server, "/persons", new PersonRouter(), Auth.filter(true));
        register(server, "/organizations", new OrganizationRouter(), Auth.filter(true));
        register(server, "/web-pages", new WebPageRouter(), Auth.filter(true));
        register(server, "/image-objects", new ImageObjectRouter(), Auth.filter(true));
        register(server, "/video-objects", new VideoObjectRouter(), Auth.filter(true));
        register(server, "/audio-objects", new AudioObjectRouter(), Auth.filter(true));
        register(server, "/category-codes", new CategoryCodeRouter(), Auth.filter(true));
        register(server, "/category-code-sets", new CategoryCodeSetRouter(), Auth.filter(true));
        register(server, "/defined-terms", new DefinedTermRouter(), Auth.filter(true));
        register(server, "/defined-term-sets", new DefinedTermSetRouter(), Auth.filter(true));
        register(server, "/comments", new CommentRouter(), Auth.filter(true));
        register(server, "/web-sites", new WebSiteRouter(), Auth.filter(true));
        register(server, "/site-navigation-elements", new SiteNavigationElementRouter(), Auth.filter(true));
        register(server, "/", exchange -> {
            Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
        }, Auth.filter(true));
        return server;
    }

    // Registers a context behind the rate-limit filter, which counts every request
    // and therefore runs first, then the optional auth filter. Every context is
    // rate limited — including /health and OPTIONS preflights.
    private static void register(HttpServer server, String path, HttpHandler handler, Filter authFilter) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(RateLimit.filter());
        if (authFilter != null) {
            context.getFilters().add(authFilter);
        }
    }
}
