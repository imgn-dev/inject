package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.lang.annotation.Annotation;

/**
 * Wraps a provider to give it a lifecycle. Implementations are discovered via {@link java.util.ServiceLoader} and keyed
 * by {@link #annotationType()}.
 */
public interface Scope {
    /**
     * Returns the scoping annotation this scope handles.
     *
     * @return the scoping annotation this scope handles
     */
    Class<? extends Annotation> annotationType();

    /**
     * Returns {@code provider} wrapped with this scope's lifecycle.
     *
     * @param provider the unscoped provider
     * @param <T> the provided type
     * @return {@code provider} wrapped with this scope's lifecycle
     */
    <T> Provider<T> wrap(Provider<T> provider);
}
