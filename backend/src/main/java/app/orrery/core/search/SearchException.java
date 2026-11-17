package app.orrery.core.search;

/** A search provider call that failed, classified so the service knows whether to fail over. */
public final class SearchException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind { AUTH, RATE_LIMITED, QUOTA, BAD_REQUEST, UNAVAILABLE, TIMEOUT, CANCELLED }

    private final Kind kind;
    private final String provider;

    public SearchException(Kind kind, String provider, String message) {
        super(message);
        this.kind = kind;
        this.provider = provider;
    }

    public Kind kind() {
        return kind;
    }

    public String provider() {
        return provider;
    }

    /** Plain-language explanation for logs and for the model's tool result. */
    public String describe() {
        return switch (kind) {
            case AUTH -> provider + " rejected the API key";
            case RATE_LIMITED -> provider + " is rate limiting requests";
            case QUOTA -> provider + " plan limit reached";
            case BAD_REQUEST -> provider + " rejected the query (" + getMessage() + ")";
            case UNAVAILABLE -> provider + " is unavailable (" + getMessage() + ")";
            case TIMEOUT -> provider + " timed out";
            case CANCELLED -> "search cancelled";
        };
    }

    static Kind fromStatus(int status) {
        if (status == 401 || status == 403) return Kind.AUTH;
        if (status == 429) return Kind.RATE_LIMITED;
        if (status == 402 || status == 432 || status == 433) return Kind.QUOTA;
        if (status == 400 || status == 404 || status == 422) return Kind.BAD_REQUEST;
        return Kind.UNAVAILABLE;
    }
}
