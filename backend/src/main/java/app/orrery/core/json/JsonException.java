package app.orrery.core.json;

/** Malformed JSON, or a value that cannot be written as JSON. */
public final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
