package be.imgn.inject.context;

import java.lang.annotation.Annotation;

import be.imgn.inject.Provider;

/**
 * Wraps a provider to give it a lifecycle. Implementations are discovered via {@link java.util.ServiceLoader} and keyed
 * by {@link #annotationType()}.
 */
public interface Scope {
    /** {@return the scoping annotation this scope handles} */
    Class<? extends Annotation> annotationType();

    /**
     * {@return {@code provider} wrapped with this scope's lifecycle}
     *
     * @param provider the unscoped provider
     * @param <T> the provided type
     */
    <T> Provider<T> wrap(Provider<T> provider);
}
