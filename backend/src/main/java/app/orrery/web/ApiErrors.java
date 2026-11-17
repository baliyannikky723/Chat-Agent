package app.orrery.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every API error has the same shape: {"error": {"code": "...", "message": "..."}}.
 * Spring's own errors (unknown path, wrong method) keep their status instead of becoming 500s,
 * and a browser that closed an event stream is not logged as a server failure.
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(400, "bad_request", "The request body isn't valid JSON.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        if (e instanceof ErrorResponse spring) {
            int status = spring.getStatusCode().value();
            return body(status, status == 404 ? "not_found" : "bad_request", status == 404 ? "Not found." : "That request isn't supported.");
        }
        if (clientWentAway(e)) return null; // nothing left to answer
        log.error("request failed", e);
        return body(500, "internal", "Something went wrong on the server.");
    }

    static ResponseEntity<Map<String, Object>> body(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message == null ? "" : message)));
    }

    /** Matched by name so this compiles against any servlet container and Spring version. */
    static boolean clientWentAway(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.equals("AsyncRequestNotUsableException") || name.equals("ClientAbortException")) return true;
            if (t instanceof IOException && String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT).contains("broken pipe")) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
