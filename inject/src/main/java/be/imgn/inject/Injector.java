package be.imgn.inject;

import be.imgn.inject.context.Context;

/** The user-facing entry point for obtaining instances and providers. */
public interface Injector {
    /**
     * {@return an instance of {@code type}}
     *
     * @param type the requested type
     * @param <T> the requested type
     */
    <T> T getInstance(Class<T> type);

    /**
     * {@return an instance for {@code key}}
     *
     * @param key the requested key
     * @param <T> the requested type
     */
    <T> T getInstance(Key<T> key);

    /**
     * {@return an instance of the generic {@code type}}
     *
     * @param type the requested type literal
     * @param <T> the requested type
     */
    <T> T getInstance(TypeLiteral<T> type);

    /**
     * {@return a provider of {@code type}}
     *
     * @param type the requested type
     * @param <T> the requested type
     */
    <T> Provider<T> getProvider(Class<T> type);

    /**
     * {@return a provider of the generic {@code type}}
     *
     * @param type the requested type literal
     * @param <T> the requested type
     */
    <T> Provider<T> getProvider(TypeLiteral<T> type);

    /**
     * {@return the provider for {@code key}}
     *
     * @param key the requested key
     * @param <T> the requested type
     */
    <T> Provider<T> getProvider(Key<T> key);

    /** {@return a new injector backed by all registered factories and scopes} */
    static Injector create() {
        return new DefaultInjector(Context.create());
    }
}
