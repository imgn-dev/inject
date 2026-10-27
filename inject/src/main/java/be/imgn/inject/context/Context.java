package be.imgn.inject.context;

import be.imgn.inject.InterceptorResolutionException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The internal resolution engine. Richer than {@code Injector} but accessible to {@link Factory} so generated factories
 * can resolve their dependencies. The default implementation is {@link DefaultContext}.
 */
public interface Context {
    /**
     * Returns the provider bound to {@code key}.
     *
     * @param key the binding key
     * @param <T> the bound type
     * @return the provider bound to {@code key}
     * @throws be.imgn.inject.UnsatisfiedDependencyException if no binding exists
     */
    <T> Provider<T> getProvider(Key<T> key);

    /**
     * Returns the definition bound to {@code key}, if any.
     *
     * <p>Introspection only; the default is empty so minimal {@code Context} implementations need not supply it.
     *
     * @param key the binding key, qualified or not
     * @return the definition bound to {@code key}, if any
     */
    default Optional<BeanDefinition> getBeanDefinition(Key<?> key) {
        return Optional.empty();
    }

    /**
     * Returns every registered definition whose bean is annotated with an annotation carrying {@code stereotype}.
     *
     * @param stereotype the meta-annotation to filter by, such as {@code QualifyingAnnotation.class}
     * @return every registered definition whose bean is annotated with an annotation carrying {@code stereotype}
     */
    default List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype) {
        return List.of();
    }

    /**
     * Returns the provider for the interceptor handling {@code annotationType}.
     *
     * <p>The default rejects every lookup, so minimal {@code Context} implementations need not supply an index.
     *
     * @param annotationType the intercepting annotation
     * @return the provider for the interceptor handling {@code annotationType}
     * @throws InterceptorResolutionException if no bean is annotated {@code @Intercepts} for it
     */
    default Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(annotationType, "annotationType");
        throw new InterceptorResolutionException("No interceptor bean for @" + annotationType.getSimpleName()
                + "; annotate a bean with @Intercepts(" + annotationType.getSimpleName() + ".class).");
    }

    /**
     * Reports a fully built instance to the {@link be.imgn.inject.ConstructionListener}s, with the injector of this
     * context. Generated factories call this for every instance they build. The default reports nothing.
     *
     * @param instance the fully built instance
     * @param <T> the instance type
     * @return {@code instance}
     */
    default <T> T constructed(T instance) {
        return Objects.requireNonNull(instance, "instance");
    }

    /**
     * Creates the instance of a child-local assisted product, and caches it in the nearest context of the chain that
     * hosts its scope, by its key. Generated assisted factories call this for a recipe with a child-local scope.
     *
     * <p>The default rejects every call, so minimal {@code Context} implementations need not host scopes.
     *
     * @param product the product to create
     * @param preDestroy the product's {@code @PreDestroy} callback, run when its host closes, or {@code null}
     * @param instance builds the instance through the context it is given: the host's, so the product's dependencies
     *     resolve where it is cached
     * @param <T> the product type
     * @return the created instance
     * @throws ScopeResolutionException if no context of the chain hosts the product's scope
     * @throws IllegalStateException if the host already holds an instance of the product
     */
    default <T> T createScoped(
            ScopedProduct product,
            @Nullable Consumer<? super T> preDestroy,
            Function<? super Context, ? extends T> instance) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(instance, "instance");
        throw new ScopeResolutionException("Cannot create " + product.key() + " with " + product.creator() + "(...):"
                + " no injector in this chain hosts @" + product.scope().getSimpleName() + ".");
    }

    /**
     * Destroys this context's scoped instances (invoking their {@code @PreDestroy} callbacks) and marks it closed. The
     * default is a no-op for minimal implementations. Idempotent.
     */
    default void close() {}
}
