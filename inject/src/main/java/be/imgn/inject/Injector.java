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
import be.imgn.inject.context.ChildContext;
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
public interface Injector extends AutoCloseable {
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

    /** {@return a builder for a child of this injector: its bindings layer over this injector's graph} */
    ChildBuilder childBuilder();

    /**
     * Closes this injector: invokes {@code @PreDestroy} on every scoped instance it created, in reverse creation order,
     * then rejects further resolution. Idempotent. Child injectors own no scoped instances, so closing a child destroys
     * nothing and does not close its parent.
     */
    @Override
    void close();

    /** {@return a new injector backed by all registered factories and scopes} */
    static Injector create() {
        DefaultContext context = new DefaultContext();
        DefaultInjector injector = new DefaultInjector(context);
        context.setInjector(injector);
        return injector;
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

        /**
         * {@return this builder, binding a provider evaluated on every resolution for {@code type}}
         *
         * @param type the bound type
         * @param provider the provider to consult for that type
         * @param <T> the bound type
         */
        public <T> Builder bindProvider(Class<T> type, Provider<T> provider) {
            return bindProvider(Key.of(type), provider);
        }

        /**
         * {@return this builder, binding a provider evaluated on every resolution for {@code key}}
         *
         * @param key the binding key
         * @param provider the provider to consult for that key
         * @param <T> the bound type
         */
        public <T> Builder bindProvider(Key<T> key, Provider<T> provider) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(provider, "provider");
            requireBindable(key);
            overrides.put(key, provider);
            return this;
        }

        /** {@return a new injector applying the configured overrides} */
        public Injector build() {
            DefaultContext context = new DefaultContext(Map.copyOf(overrides));
            DefaultInjector injector = new DefaultInjector(context);
            context.setInjector(injector);
            return injector;
        }

        static void requireBindable(Key<?> key) {
            Type type = key.type();
            Type raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
            if (raw == Injector.class || raw == Context.class || raw == Factory.class) {
                throw new IllegalArgumentException(
                        ((Class<?>) raw).getSimpleName() + " is a framework type and cannot be bound.");
            }
        }
    }

    /**
     * Builds a child {@link Injector}: explicit instance and provider bindings layered over the parent's graph.
     * Shadowing a parent binding affects resolutions through the child only; binding the same key twice in one builder
     * is an error.
     */
    final class ChildBuilder {
        private final Context parentContext;
        private final Map<Key<?>, Provider<?>> bindings = new HashMap<>();

        ChildBuilder(Context parentContext) {
            this.parentContext = parentContext;
        }

        /**
         * {@return this builder, binding {@code instance} as the unqualified key for {@code type}}
         *
         * @param type the bound type
         * @param instance the instance to return for that type
         * @param <T> the bound type
         */
        public <T> ChildBuilder bind(Class<T> type, T instance) {
            return bind(Key.of(type), instance);
        }

        /**
         * {@return this builder, binding {@code instance} to {@code key}}
         *
         * @param key the binding key
         * @param instance the instance to return for that key
         * @param <T> the bound type
         */
        public <T> ChildBuilder bind(Key<T> key, T instance) {
            Objects.requireNonNull(instance, "instance");
            return bindProvider(key, () -> instance);
        }

        /**
         * {@return this builder, binding a provider evaluated on every resolution through the child}
         *
         * @param type the bound type
         * @param provider the provider to consult for that type
         * @param <T> the bound type
         */
        public <T> ChildBuilder bindProvider(Class<T> type, Provider<T> provider) {
            return bindProvider(Key.of(type), provider);
        }

        /**
         * {@return this builder, binding a provider evaluated on every resolution through the child}
         *
         * @param key the binding key
         * @param provider the provider to consult for that key
         * @param <T> the bound type
         */
        public <T> ChildBuilder bindProvider(Key<T> key, Provider<T> provider) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(provider, "provider");
            Builder.requireBindable(key);
            if (bindings.putIfAbsent(key, provider) != null) {
                throw new IllegalArgumentException(key + " is already bound in this builder.");
            }
            return this;
        }

        /** {@return the child injector applying these bindings over the parent} */
        public Injector build() {
            ChildContext context = new ChildContext(parentContext, bindings);
            DefaultInjector child = new DefaultInjector(context);
            context.setInjector(child);
            return child;
        }
    }
}
