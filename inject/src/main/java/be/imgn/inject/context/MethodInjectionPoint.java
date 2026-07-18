package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.util.List;

import be.imgn.inject.Key;

/**
 * A method injection point.
 *
 * @param factory the factory owning this injection point
 * @param methodName the method name (for inspection)
 * @param parameters the method parameters, in order
 * @param annotationMetadata annotations present on the method
 */
public record MethodInjectionPoint(
        Factory<?> factory, String methodName, List<Parameter> parameters, AnnotationMetadata annotationMetadata)
        implements InjectionPoint {

    /**
     * @param factory the factory owning this injection point
     * @param methodName the method name
     * @param parameters the method parameters, in order
     * @param annotationMetadata annotations present on the method
     */
    public MethodInjectionPoint {
        requireNonNull(factory, "factory");
        requireNonNull(methodName, "methodName");
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
