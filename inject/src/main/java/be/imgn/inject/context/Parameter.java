package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Type;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Qualifier;

/**
 * Describes one constructor or method parameter to inject.
 *
 * @param name the parameter name (for inspection)
 * @param type the parameter type (its inner type when {@code isProvider})
 * @param qualifier the qualifier, or {@code null}
 * @param annotationMetadata annotations present on the parameter
 * @param isProvider whether the declared parameter is a {@code Provider} of {@code type}
 */
public record Parameter(
        String name,
        Type type,
        @Nullable Qualifier qualifier,
        AnnotationMetadata annotationMetadata,
        boolean isProvider) {

    /**
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifier the qualifier
     * @param annotationMetadata annotations present on the parameter
     * @param isProvider provider flag
     */
    public Parameter {
        requireNonNull(name);
        requireNonNull(type);
        requireNonNull(annotationMetadata);
    }

    /**
     * @param name the parameter name
     * @param type the parameter type
     */
    public Parameter(String name, Type type) {
        this(name, type, null, AnnotationMetadata.empty(), false);
    }

    /**
     * @param name the parameter name
     * @param type the parameter type
     * @param qualifier the qualifier
     */
    public Parameter(String name, Type type, @Nullable Qualifier qualifier) {
        this(name, type, qualifier, AnnotationMetadata.empty(), false);
    }

    /**
     * @param name the parameter name
     * @param type the parameter type
     * @param isProvider provider flag
     */
    public Parameter(String name, Type type, boolean isProvider) {
        this(name, type, null, AnnotationMetadata.empty(), isProvider);
    }

    /**
     * @param name the name
     * @param type the type
     * @param qualifier the qualifier
     * @param isProvider provider flag
     */
    public Parameter(String name, Type type, @Nullable Qualifier qualifier, boolean isProvider) {
        this(name, type, qualifier, AnnotationMetadata.empty(), isProvider);
    }

    /** {@return whether this parameter has a qualifier} */
    public boolean hasQualifier() {
        return qualifier != null;
    }
}
