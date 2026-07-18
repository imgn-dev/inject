package be.imgn.inject;

import java.util.Optional;

/**
 * A binding identity: a type, optionally combined with a {@link Qualifier}.
 *
 * @param <T> the bound type
 */
public sealed interface Key<T> permits SimpleKey, QualifiedKey {
    /** {@return the bound type} */
    Class<T> type();

    /** {@return the qualifier, or empty for an unqualified key} */
    Optional<Qualifier> qualifier();

    /**
     * {@return an unqualified key for {@code type}}
     *
     * @param type the bound type
     * @param <T> the bound type
     */
    static <T> Key<T> of(Class<T> type) {
        return new SimpleKey<>(type);
    }

    /**
     * {@return a qualified key}
     *
     * @param type the bound type
     * @param qualifier the qualifier
     * @param <T> the bound type
     */
    static <T> Key<T> of(Class<T> type, Qualifier qualifier) {
        return new QualifiedKey<>(type, qualifier);
    }
}
