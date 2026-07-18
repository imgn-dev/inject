package be.imgn.inject;

/**
 * The core abstraction: supplies an instance of {@code T} on demand.
 *
 * @param <T> the provided type
 */
@FunctionalInterface
public interface Provider<T> {
    /** {@return a provided instance of {@code T}} */
    T get();
}
