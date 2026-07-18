package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Key;
import be.imgn.inject.Qualifier;

/**
 * A field injection point.
 *
 * @param factory the factory owning this injection point
 * @param fieldName the field name (for inspection)
 * @param fieldType the field's declared type (its inner type when {@code isProvider})
 * @param qualifier the qualifier, or {@code null}
 * @param annotationMetadata annotations present on the field
 * @param isProvider whether the declared field is a {@code Provider} of {@code fieldType}
 */
public record FieldInjectionPoint(
        Factory<?> factory,
        String fieldName,
        Class<?> fieldType,
        @Nullable Qualifier qualifier,
        AnnotationMetadata annotationMetadata,
        boolean isProvider)
        implements InjectionPoint {

    /**
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param qualifier the qualifier, or {@code null}
     * @param annotationMetadata annotations present on the field
     * @param isProvider provider flag
     */
    public FieldInjectionPoint {
        requireNonNull(factory, "factory");
        requireNonNull(fieldName, "fieldName");
        requireNonNull(fieldType, "fieldType");
        requireNonNull(annotationMetadata, "annotationMetadata");
    }

    /**
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param annotationMetadata annotations present on the field
     */
    public FieldInjectionPoint(
            Factory<?> factory, String fieldName, Class<?> fieldType, AnnotationMetadata annotationMetadata) {
        this(factory, fieldName, fieldType, null, annotationMetadata, false);
    }

    /**
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param qualifier the qualifier, or {@code null}
     * @param annotationMetadata annotations present on the field
     */
    public FieldInjectionPoint(
            Factory<?> factory,
            String fieldName,
            Class<?> fieldType,
            @Nullable Qualifier qualifier,
            AnnotationMetadata annotationMetadata) {
        this(factory, fieldName, fieldType, qualifier, annotationMetadata, false);
    }

    /**
     * {@return the key for this field}
     *
     * @param <T> the field's dependency type
     */
    @SuppressWarnings("unchecked")
    public <T> Key<T> key() {
        return (Key<T>) Key.of(fieldType, qualifier);
    }
}
