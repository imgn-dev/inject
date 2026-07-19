package be.imgn.inject;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;

/**
 * Concrete {@link Named} instance honouring the {@link Annotation} equality and hash-code contract, so it compares
 * equal to any {@code @Named} carrying the same value regardless of how that instance was produced.
 */
final class NamedImpl implements Named {
    private final String value;

    NamedImpl(String value) {
        this.value = requireNonNull(value, "value");
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return Named.class;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Named other && value.equals(other.value());
    }

    @Override
    public int hashCode() {
        // Annotation.hashCode contract: (127 * "value".hashCode()) ^ value.hashCode().
        return (127 * "value".hashCode()) ^ value.hashCode();
    }

    @Override
    public String toString() {
        return "@" + Named.class.getName() + "(value=\"" + value + "\")";
    }
}
