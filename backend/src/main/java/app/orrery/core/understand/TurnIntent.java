package app.orrery.core.understand;

/**
 * Deterministic reading of what a message asks for.
 *
 * @param recall    refers to an earlier conversation ("I asked you", "we shortlisted")
 * @param verify    asks whether something is still true or has changed
 * @param sinceThen the change window starts at the recalled conversation ("since then")
 * @param freshness probably needs live information (prices, news, "latest")
 * @param smallTalk a greeting or thanks: answer instantly, no memory, no search
 */
public record TurnIntent(boolean recall, boolean verify, boolean sinceThen, boolean freshness, boolean smallTalk) {

    public static final TurnIntent SMALL_TALK = new TurnIntent(false, false, false, false, true);

    /** Memory must be loaded before the model sees the message. */
    public boolean needsMemory() {
        return recall || verify;
    }
}
