package app.orrery.web;

/** An error with an HTTP status and a stable code the UI can act on. */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }
}
