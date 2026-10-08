package be.imgn.inject.examples;

/** A type without an injectable constructor, built through an assisted {@code @Provides} recipe. */
public final class LegacyStamp {
    private final String text;

    /** @param text the stamp text */
    public LegacyStamp(String text) {
        this.text = text;
    }

    /**
     * Returns the stamp text.
     *
     * @return the stamp text
     */
    public String text() {
        return text;
    }
}
