package be.imgn.inject;

/** Factories for built-in qualifier annotation instances. */
public final class Names {

    private Names() {}

    /**
     * {@return a {@link Named} instance for use as a qualifier}
     *
     * <p>The returned instance obeys the {@link java.lang.annotation.Annotation} equality and hash-code contract, so it
     * is equal to any other {@code @Named} with the same value, however obtained.
     *
     * @param value the qualifier name
     */
    public static Named named(String value) {
        return new NamedImpl(value);
    }
}
