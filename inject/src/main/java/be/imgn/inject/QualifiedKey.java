package be.imgn.inject;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Key} carrying a {@link Qualifier}.
 *
 * @param type the bound type
 * @param qualifierValue the qualifier
 * @param <T> the bound type
 */
public record QualifiedKey<T>(Class<T> type, Qualifier qualifierValue) implements Key<T> {
    /**
     * @param type the bound type
     * @param qualifierValue the qualifier
     */
    public QualifiedKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(qualifierValue, "qualifierValue");
    }

    @Override
    public Optional<Qualifier> qualifier() {
        return Optional.of(qualifierValue);
    }
}
