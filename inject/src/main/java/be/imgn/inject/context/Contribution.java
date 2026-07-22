package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Key;

/**
 * A factory's collection membership: instead of binding under its own keys, the factory's bean joins the collection
 * bound at {@code collectionKey}.
 *
 * @param collectionKey the collection binding key, e.g. {@code Key.of(Types.setOf(Handler.class))}
 * @param mapKey the map key for a {@code Map} contribution, or {@code null} for a {@code Set} contribution
 */
public record Contribution(Key<?> collectionKey, @Nullable String mapKey) {
    /**
     * @param collectionKey the collection binding key
     * @param mapKey the map key, or {@code null} for a set contribution
     */
    public Contribution {
        requireNonNull(collectionKey, "collectionKey");
    }
}
