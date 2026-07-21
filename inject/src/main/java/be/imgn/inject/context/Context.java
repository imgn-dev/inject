package be.imgn.inject.context;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * {@return the definition bound to {@code key}, if any}
     *
     * <p>Introspection only; the default is empty so minimal {@code Context} implementations need not supply it.
     *
     * @param key the binding key, qualified or not
     */
    default Optional<BeanDefinition> getBeanDefinition(Key<?> key) {
        return Optional.empty();
    }

    /**
     * {@return every registered definition whose bean is annotated with an annotation carrying {@code stereotype}}
     *
     * @param stereotype the meta-annotation to filter by, such as {@code QualifyingAnnotation.class}
     */
    default List<BeanDefinition> getBeanDefinitionsByStereotype(Class<? extends Annotation> stereotype) {
        return List.of();
    }

    /** {@return a context loaded from all registered {@code Factory} and {@code Scope} services} */
    static Context create() {
        return new DefaultContext();
    }

    /**
     * {@return a context loaded from all registered services, with {@code overrides} shadowing discovered bindings}
     *
     * @param overrides providers that replace any discovered binding for the same key; primarily a test seam
     */
    static Context create(Map<Key<?>, Provider<?>> overrides) {
        return new DefaultContext(overrides);
    }
}
