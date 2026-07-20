package be.imgn.inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import be.imgn.inject.context.Context;

/**
 * The user-facing entry point for obtaining instances and providers.
 *
 * <p>Each {@link #create()} (or {@link #builder()} {@link Builder#build()}) call produces a fresh, independent graph:
 * its singletons are created once <em>within that injector</em> and are not shared with any other injector. Discard an
 * injector to discard its singletons.
 *
 * <p>Prefer constructor injection: annotate a single constructor with {@code @Inject} so a bean's dependencies are
 * final, explicit, and available the moment it is constructed. Field and method injection exist for cases constructor
 * injection cannot reach (e.g. framework-managed or cyclic wiring) and leave a window where the bean is only partly
 * initialised.
 */
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

    /** {@return a builder for an injector whose instance bindings shadow discovered ones (a test seam)} */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builds an {@link Injector} that overlays explicit instance bindings on top of the discovered graph. An override
     * replaces any discovered binding for the same key, including where that key is an injected dependency of another
     * bean, which makes it a convenient seam for substituting fakes in tests.
     */
    final class Builder {
        private final Map<Key<?>, Provider<?>> overrides = new HashMap<>();

        private Builder() {}

        /**
         * {@return this builder, binding {@code instance} as the unqualified key for {@code type}}
         *
         * @param type the bound type
         * @param instance the instance to return for that type
         * @param <T> the bound type
         */
        public <T> Builder bind(Class<T> type, T instance) {
            return bind(Key.of(type), instance);
        }

        /**
         * {@return this builder, binding {@code instance} to {@code key}}
         *
         * @param key the binding key
         * @param instance the instance to return for that key
         * @param <T> the bound type
         */
        public <T> Builder bind(Key<T> key, T instance) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(instance, "instance");
            overrides.put(key, () -> instance);
            return this;
        }

        /** {@return a new injector applying the configured overrides} */
        public Injector build() {
            return new DefaultInjector(Context.create(Map.copyOf(overrides)));
        }
    }
}
