package cms;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import cms.routers.BlogPostingRouter;
import cms.routers.PersonRouter;
import cms.routers.WebPageRouter;
import cms.routers.ImageObjectRouter;
import cms.routers.CategoryCodeRouter;
import cms.routers.CategoryCodeSetRouter;
import cms.routers.DefinedTermRouter;
import cms.routers.DefinedTermSetRouter;
import cms.routers.CommentRouter;
import cms.routers.WebSiteRouter;

public final class Server {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3006"));
        String host = System.getenv().getOrDefault("HOST", "0.0.0.0");
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
        server.createContext("/blog-postings", new BlogPostingRouter());
        server.createContext("/persons", new PersonRouter());
        server.createContext("/web-pages", new WebPageRouter());
        server.createContext("/image-objects", new ImageObjectRouter());
        server.createContext("/category-codes", new CategoryCodeRouter());
        server.createContext("/category-code-sets", new CategoryCodeSetRouter());
        server.createContext("/defined-terms", new DefinedTermRouter());
        server.createContext("/defined-term-sets", new DefinedTermSetRouter());
        server.createContext("/comments", new CommentRouter());
        server.createContext("/web-sites", new WebSiteRouter());
        server.createContext("/", exchange -> {
            Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
        });
        return server;
    }
}
