package cms;

import cms.models.Account;
import cms.routers.AuthRouter;

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
        server.createContext("/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
                return;
            }
            Http.json(exchange, 200, java.util.Map.of("status", "ok"));
        });
        server.createContext("/auth", new AuthRouter()).getFilters().add(Auth.filter(false));
        server.createContext("/blog-postings", new BlogPostingRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/persons", new PersonRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/organizations", new OrganizationRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/web-pages", new WebPageRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/image-objects", new ImageObjectRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/video-objects", new VideoObjectRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/audio-objects", new AudioObjectRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/category-codes", new CategoryCodeRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/category-code-sets", new CategoryCodeSetRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/defined-terms", new DefinedTermRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/defined-term-sets", new DefinedTermSetRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/comments", new CommentRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/web-sites", new WebSiteRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/site-navigation-elements", new SiteNavigationElementRouter()).getFilters().add(Auth.filter(true));
        server.createContext("/", exchange -> {
            Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
        }).getFilters().add(Auth.filter(true));
        return server;
    }
}
