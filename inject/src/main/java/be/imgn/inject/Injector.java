package be.imgn.inject;

import be.imgn.inject.context.BeanDefinition;
import be.imgn.inject.context.ChildContext;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.DefaultContext;
import be.imgn.inject.context.Factory;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
     * Returns an instance of {@code type}.
     *
     * @param type the requested type
     * @param <T> the requested type
     * @return an instance of {@code type}
     */
    <T> T getInstance(Class<T> type);

    /**
     * Returns an instance for {@code key}.
     *
     * @param key the requested key
     * @param <T> the requested type
     * @return an instance for {@code key}
     */
    <T> T getInstance(Key<T> key);

    /**
     * Returns an instance of the generic {@code type}.
     *
     * @param type the requested type literal
     * @param <T> the requested type
     * @return an instance of the generic {@code type}
     */
    <T> T getInstance(TypeLiteral<T> type);

    /**
     * Returns a provider of {@code type}.
     *
     * @param type the requested type
     * @param <T> the requested type
     * @return a provider of {@code type}
     */
    <T> Provider<T> getProvider(Class<T> type);

    /**
     * Returns a provider of the generic {@code type}.
     *
     * @param type the requested type literal
     * @param <T> the requested type
     * @return a provider of the generic {@code type}
     */
    <T> Provider<T> getProvider(TypeLiteral<T> type);

    /**
     * Returns the provider for {@code key}.
     *
     * @param key the requested key
     * @param <T> the requested type
     * @return the provider for {@code key}
     */
    <T> Provider<T> getProvider(Key<T> key);

    /**
     * Returns the definition bound to {@code key}, if any.
     *
     * <p>A read-only view for inspecting the graph — the bean's key, declared framework annotations, and injection
     * points — not for building instances.
     *
     * @param key the binding key, qualified or not
     * @return the definition bound to {@code key}, if any
     */
    Optional<BeanDefinition> getBeanDefinition(Key<?> key);

    /**
     * Returns every bean annotated with an annotation carrying {@code stereotype}.
     *
     * <p>For example {@code getBeanDefinitionsByStereotype(ScopingAnnotation.class)} lists every scoped bean.
     *
     * @param stereotype the meta-annotation to filter by
     * @return every bean annotated with an annotation carrying {@code stereotype}
     */
    List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype);

    /**
     * Returns a builder for a child of this injector: its bindings layer over this injector's graph.
     *
     * @return a builder for a child of this injector: its bindings layer over this injector's graph
     */
    ChildBuilder childBuilder();

    /**
     * Returns a builder for a child of this injector that hosts the child-local {@code scope}: its bindings layer over
     * this injector's graph, and it builds and caches the instances of {@code scope}'s beans resolved through it or its
     * descendants.
     *
     * <p>A scoping annotation is child-local when no {@link be.imgn.inject.context.Scope} service handles it. Each bean
     * of such a scope is created once per hosting child, built through that child, and cached there. Resolving it
     * through an injector with no hosting child in its chain fails with a {@link ScopeResolutionException}. Hosting
     * children nest: the nearest one in the chain hosts the bean.
     *
     * @param scope the child-local scope the child hosts
     * @return a builder for a child of this injector that hosts {@code scope}
     */
    ChildBuilder scopedChildBuilder(Class<? extends Annotation> scope);

    /**
     * Closes this injector: rejects further resolution through it, including through the providers it returned, closes
     * its live child injectors, newest first, then invokes {@code @PreDestroy} on every scoped instance it created, in
     * reverse creation order. A child injector's scoped instances are those of the scope it hosts. When a callback or a
     * child fails, the others still run, and the first failure is thrown with the later ones suppressed. Closing a
     * child never closes its parent. Idempotent.
     */
    @Override
    void close();

    /**
     * Returns a new injector backed by all registered factories and scopes.
     *
     * @return a new injector backed by all registered factories and scopes
     * @throws UnsatisfiedDependencyException if a bean depends on a key nothing binds; the message lists every such
     *     dependency
     */
    static Injector create() {
        DefaultContext context = new DefaultContext();
        DefaultInjector injector = new DefaultInjector(context);
        context.setInjector(injector);
        return injector;
    }

    /**
     * Returns a builder for an injector whose instance bindings shadow discovered ones (a test seam).
     *
     * @return a builder for an injector whose instance bindings shadow discovered ones (a test seam)
     */
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
         * Returns this builder, binding {@code instance} as the unqualified key for {@code type}.
         *
         * @param type the bound type
         * @param instance the instance to return for that type
         * @param <T> the bound type
         * @return this builder, binding {@code instance} as the unqualified key for {@code type}
         */
        public <T> Builder bind(Class<T> type, T instance) {
            return bind(Key.of(type), instance);
        }

        /**
         * Returns this builder, binding {@code instance} to {@code key}.
         *
         * @param key the binding key
         * @param instance the instance to return for that key
         * @param <T> the bound type
         * @return this builder, binding {@code instance} to {@code key}
         */
        public <T> Builder bind(Key<T> key, T instance) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(instance, "instance");
            requireBindable(key);
            overrides.put(key, () -> instance);
            return this;
        }

        /**
         * Returns this builder, binding a provider evaluated on every resolution for {@code type}.
         *
         * @param type the bound type
         * @param provider the provider to consult for that type
         * @param <T> the bound type
         * @return this builder, binding a provider evaluated on every resolution for {@code type}
         */
        public <T> Builder bindProvider(Class<T> type, Provider<T> provider) {
            return bindProvider(Key.of(type), provider);
        }

        /**
         * Returns this builder, binding a provider evaluated on every resolution for {@code key}.
         *
         * @param key the binding key
         * @param provider the provider to consult for that key
         * @param <T> the bound type
         * @return this builder, binding a provider evaluated on every resolution for {@code key}
         */
        public <T> Builder bindProvider(Key<T> key, Provider<T> provider) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(provider, "provider");
            requireBindable(key);
            overrides.put(key, provider);
            return this;
        }

        /**
         * Returns a new injector applying the configured overrides.
         *
         * @return a new injector applying the configured overrides
         * @throws UnsatisfiedDependencyException if a bean depends on a key that neither a discovered factory nor an
         *     override binds; the message lists every such dependency
         */
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
        private final @Nullable Class<? extends Annotation> scope;
        private final Map<Key<?>, Provider<?>> bindings = new HashMap<>();

        ChildBuilder(Context parentContext, @Nullable Class<? extends Annotation> scope) {
            this.parentContext = parentContext;
            this.scope = scope;
        }

        /**
         * Returns this builder, binding {@code instance} as the unqualified key for {@code type}.
         *
         * @param type the bound type
         * @param instance the instance to return for that type
         * @param <T> the bound type
         * @return this builder, binding {@code instance} as the unqualified key for {@code type}
         */
        public <T> ChildBuilder bind(Class<T> type, T instance) {
            return bind(Key.of(type), instance);
        }

        /**
         * Returns this builder, binding {@code instance} to {@code key}.
         *
         * @param key the binding key
         * @param instance the instance to return for that key
         * @param <T> the bound type
         * @return this builder, binding {@code instance} to {@code key}
         */
        public <T> ChildBuilder bind(Key<T> key, T instance) {
            Objects.requireNonNull(instance, "instance");
            return bindProvider(key, () -> instance);
        }

        /**
         * Returns this builder, binding a provider evaluated on every resolution through the child.
         *
         * @param type the bound type
         * @param provider the provider to consult for that type
         * @param <T> the bound type
         * @return this builder, binding a provider evaluated on every resolution through the child
         */
        public <T> ChildBuilder bindProvider(Class<T> type, Provider<T> provider) {
            return bindProvider(Key.of(type), provider);
        }

        /**
         * Returns this builder, binding a provider evaluated on every resolution through the child.
         *
         * @param key the binding key
         * @param provider the provider to consult for that key
         * @param <T> the bound type
         * @return this builder, binding a provider evaluated on every resolution through the child
         * @throws IllegalArgumentException if {@code key} names a framework type or is already bound in this builder
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

        /**
         * Returns the child injector applying these bindings over the parent.
         *
         * @return the child injector applying these bindings over the parent
         * @throws IllegalArgumentException if the child hosts a scope that a {@link be.imgn.inject.context.Scope}
         *     service handles, so it is not child-local
         */
        public Injector build() {
            ChildContext context = scope == null
                    ? new ChildContext(parentContext, bindings)
                    : new ChildContext(parentContext, bindings, scope);
            DefaultInjector child = new DefaultInjector(context);
            context.setInjector(child);
            return child;
        }
    }
}
