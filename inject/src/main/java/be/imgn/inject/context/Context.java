package be.imgn.inject.context;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

/**
 * The internal resolution engine. Richer than {@code Injector} but accessible to {@link Factory} so generated factories
 * can resolve their dependencies.
 */
public interface Context {
    /**
     * {@return the provider bound to {@code key}}
     *
     * @param key the binding key
     * @param <T> the bound type
     * @throws be.imgn.inject.UnsatisfiedDependencyException if no binding exists
     */
    <T> Provider<T> getProvider(Key<T> key);

    /** {@return a context loaded from all registered {@code Factory} and {@code Scope} services} */
    static Context create() {
        return new DefaultContext();
    }
}
