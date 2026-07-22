package be.imgn.inject;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import be.imgn.inject.context.BeanDefinition;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.DefaultContext;
import be.imgn.inject.context.Factory;

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
 *
 * <p>The injector itself is injectable: any bean may declare an {@code Injector} (or {@code Provider<Injector>})
 * dependency and receives the injector that built it. {@code Injector}, {@code Context}, and {@code Factory} can never
 * be bound.
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

    /**
     * {@return the definition bound to {@code key}, if any}
     *
     * <p>A read-only view for inspecting the graph — the bean's key, declared framework annotations, and injection
     * points — not for building instances.
     *
     * @param key the binding key, qualified or not
     */
    Optional<BeanDefinition> getBeanDefinition(Key<?> key);

    /**
     * {@return every bean annotated with an annotation carrying {@code stereotype}}
     *
     * <p>For example {@code getBeanDefinitionsByStereotype(ScopingAnnotation.class)} lists every scoped bean.
     *
     * @param stereotype the meta-annotation to filter by
     */
    List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype);

    /** {@return a new injector backed by all registered factories and scopes} */
    static Injector create() {
        return new DefaultInjector(new DefaultContext());
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
            requireBindable(key);
            overrides.put(key, () -> instance);
            return this;
        }

        /** {@return a new injector applying the configured overrides} */
        public Injector build() {
            return new DefaultInjector(new DefaultContext(Map.copyOf(overrides)));
        }

        private static void requireBindable(Key<?> key) {
            Type type = key.type();
            Type raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
            if (raw == Injector.class || raw == Context.class || raw == Factory.class) {
                throw new IllegalArgumentException(
                        ((Class<?>) raw).getSimpleName() + " is a framework type and cannot be bound.");
            }
        }
    }
}
