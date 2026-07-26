package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Key;

/**
 * Describes one constructor or method parameter to inject. A qualifier is either a marker annotation type
 * ({@code qualifierType}) or a concrete annotation instance ({@code qualifier}); at most one is non-{@code null}.
 *
 * @param name the parameter name (for inspection)
 * @param type the parameter type (its inner type when {@code isProvider})
 * @param qualifierType the marker qualifier annotation type, or {@code null}
 * @param qualifier the qualifier annotation instance, or {@code null}
 * @param annotationMetadata annotations present on the parameter
 * @param isProvider whether the declared parameter is a {@code Provider} of {@code type}
 */
public record Parameter(
        String name,
        Type type,
        @Nullable Class<? extends Annotation> qualifierType,
        @Nullable Annotation qualifier,
        AnnotationMetadata annotationMetadata,
        boolean isProvider) {

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifierType the marker qualifier annotation type, or {@code null}
     * @param qualifier the qualifier annotation instance, or {@code null}
     * @param annotationMetadata annotations present on the parameter
     * @param isProvider provider flag
     */
    public Parameter {
        requireNonNull(name);
        requireNonNull(type);
        requireNonNull(annotationMetadata);
    }

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     */
    public Parameter(String name, Type type) {
        this(name, type, null, null, AnnotationMetadata.empty(), false);
    }

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @param isProvider provider flag
     */
    public Parameter(String name, Type type, boolean isProvider) {
        this(name, type, null, null, AnnotationMetadata.empty(), isProvider);
    }

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifierType the marker qualifier annotation type
     */
    public Parameter(String name, Type type, Class<? extends Annotation> qualifierType) {
        this(name, type, qualifierType, null, AnnotationMetadata.empty(), false);
    }

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifierType the marker qualifier annotation type
     * @param isProvider provider flag
     */
    public Parameter(String name, Type type, Class<? extends Annotation> qualifierType, boolean isProvider) {
        this(name, type, qualifierType, null, AnnotationMetadata.empty(), isProvider);
    }

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifier the qualifier annotation instance
     */
    public Parameter(String name, Type type, Annotation qualifier) {
        this(name, type, null, qualifier, AnnotationMetadata.empty(), false);
    }

    /**
     * Creates a parameter description.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifier the qualifier annotation instance
     * @param isProvider provider flag
     */
    public Parameter(String name, Type type, Annotation qualifier, boolean isProvider) {
        this(name, type, null, qualifier, AnnotationMetadata.empty(), isProvider);
    }

    /** {@return whether this parameter has a qualifier} */
    public boolean hasQualifier() {
        return qualifierType != null || qualifier != null;
    }

    /**
     * {@return the key for this parameter}
     *
     * @param <T> the dependency type
     */
    @SuppressWarnings("unchecked")
    public <T> Key<T> key() {
        return (Key<T>) (qualifier != null ? Key.of(type, qualifier) : Key.of(type, qualifierType));
    }
}
