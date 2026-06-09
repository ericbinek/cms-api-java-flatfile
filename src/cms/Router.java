package cms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public interface Router extends HttpHandler {
    @Override
    default void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                Http.preflight(exchange);
                return;
            }
            if (!dispatch(exchange, method, path, Http.requestPath(exchange))) {
                Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
            }
        } catch (Http.BodyTooLargeException ex) {
            Http.jsonError(exchange, Errors.payloadTooLarge(Http.requestPath(exchange)));
        } catch (Http.UnsupportedMediaTypeException ex) {
            Http.jsonError(exchange, Errors.unsupportedMediaType(Http.requestPath(exchange)));
        } catch (Json.JsonException ex) {
            Http.jsonError(exchange, Errors.invalidJson(Http.requestPath(exchange)));
        } catch (Exception ex) {
            System.err.println("[" + Http.requestPath(exchange) + "] " + ex.getMessage());
            Http.jsonError(exchange, Errors.internal(Http.requestPath(exchange)));
        } finally {
            exchange.close();
        }
    }

    boolean dispatch(HttpExchange exchange, String method, String path, String requestPath) throws Exception;
}
