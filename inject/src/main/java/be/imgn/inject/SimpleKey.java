package be.imgn.inject;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Key} with no qualifier.
 *
 * @param type the bound type
 * @param <T> the bound type
 */
public record SimpleKey<T>(Class<T> type) implements Key<T> {
    /** @param type the bound type */
    public SimpleKey {
        Objects.requireNonNull(type, "type");
    }

    @Override
    public Optional<Qualifier> qualifier() {
        return Optional.empty();
    }
}
