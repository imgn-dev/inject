package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.util.List;

import be.imgn.inject.Key;

/**
 * The constructor injection point of a bean.
 *
 * @param factory the factory owning this injection point
 * @param parameters the constructor parameters, in order
 * @param annotationMetadata annotations present on the constructor
 */
public record ConstructorInjectionPoint(
        Factory<?> factory, List<Parameter> parameters, AnnotationMetadata annotationMetadata)
        implements InjectionPoint {

    /**
     * @param factory the factory owning this injection point
     * @param parameters the constructor parameters, in order
     * @param annotationMetadata annotations present on the constructor
     */
    public ConstructorInjectionPoint {
        requireNonNull(factory, "factory");
        parameters = List.copyOf(requireNonNull(parameters, "parameters"));
        requireNonNull(annotationMetadata, "annotationMetadata");
    }

    /**
     * {@return the key for the parameter at {@code index}}
     *
     * @param index the zero-based parameter index
     * @param <T> the dependency type
     */
    @SuppressWarnings("unchecked")
    public <T> Key<T> keyForParameter(int index) {
        Parameter parameter = parameters.get(index);
        return (Key<T>) Key.of(parameter.type(), parameter.qualifier());
    }
}
