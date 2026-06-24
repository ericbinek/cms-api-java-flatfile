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
            // The auth middleware filter resolved the principal before routing.
            Auth.Principal principal = Auth.of(exchange);
            if (!dispatch(exchange, method, path, Http.requestPath(exchange), principal)) {
                Http.jsonError(exchange, Errors.routeNotFound(Http.requestPath(exchange)));
            }
        } catch (Http.BodyTooLargeException ex) {
            Http.jsonError(exchange, Errors.payloadTooLarge(Http.requestPath(exchange)));
        } catch (Http.UnsupportedMediaTypeException ex) {
            Http.jsonError(exchange, Errors.unsupportedMediaType(Http.requestPath(exchange)));
        } catch (Json.JsonException ex) {
            Http.jsonError(exchange, Errors.invalidJson(Http.requestPath(exchange)));
        } catch (cms.models.DuplicateException ex) {
            // A unique-key collision is reported in the existing validation
            // envelope (400), not a new error type.
            Http.jsonError(exchange, Errors.validation(ex.details(), Http.requestPath(exchange)));
        } catch (Exception ex) {
            System.err.println("[" + Http.requestPath(exchange) + "] " + ex.getMessage());
            Http.jsonError(exchange, Errors.internal(Http.requestPath(exchange)));
        } finally {
            exchange.close();
        }
    }

    boolean dispatch(HttpExchange exchange, String method, String path, String requestPath, Auth.Principal principal) throws Exception;
}
