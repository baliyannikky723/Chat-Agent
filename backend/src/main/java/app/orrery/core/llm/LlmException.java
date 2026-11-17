package app.orrery.core.llm;

import java.util.Locale;

/** A failed model call, classified so callers can retry, fall back, or explain the fix. */
public final class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind { AUTH, RATE_LIMITED, MODEL_NOT_FOUND, BAD_REQUEST, UNAVAILABLE, CANCELLED }

    private final Kind kind;
    private final int status;
    private final long retryAfterMillis;

    public LlmException(Kind kind, int status, String message, long retryAfterMillis) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public LlmException(Kind kind, String message) {
        this(kind, 0, message, 0);
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.UNAVAILABLE;
    }

    /** Maps an HTTP error response to a kind. Providers report unknown models as 400 or 404. */
    public static LlmException fromHttp(String provider, int status, String body, long retryAfterMillis) {
        String text = body == null ? "" : body;
        String lower = text.toLowerCase(Locale.ROOT);
        String message = provider + " returned HTTP " + status + ": " + shorten(text);
        boolean modelProblem = lower.contains("model_not_found")
                || (lower.contains("model") && (lower.contains("does not exist") || lower.contains("not found")
                        || lower.contains("not supported") || lower.contains("unknown model") || lower.contains("decommissioned")
                        || lower.contains("is not available") || lower.contains("no endpoints found")));
        Kind kind;
        if (status == 401 || status == 403) kind = Kind.AUTH;
        // An account with no credit answers 429 too, but waiting will not fix it.
        else if (status == 429) kind = lower.contains("insufficient_quota") || lower.contains("billing") ? Kind.AUTH : Kind.RATE_LIMITED;
        else if (status == 402) kind = Kind.AUTH;
        else if ((status == 404 || status == 400) && modelProblem) kind = Kind.MODEL_NOT_FOUND;
        else if (status == 400 || status == 404 || status == 409 || status == 413 || status == 422) kind = Kind.BAD_REQUEST;
        else if (status == 529 || status >= 500) kind = Kind.UNAVAILABLE;
        else kind = Kind.BAD_REQUEST;
        return new LlmException(kind, status, message, retryAfterMillis);
    }

    private static String shorten(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
