package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import be.imgn.inject.Key;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;

/**
 * A field injection point. A qualifier is either a marker annotation type ({@code qualifierType}) or a concrete
 * annotation instance ({@code qualifier}); at most one is non-{@code null}.
 *
 * @param factory the factory owning this injection point
 * @param fieldName the field name (for inspection)
 * @param fieldType the field's declared type (its inner type when {@code isProvider})
 * @param qualifierType the marker qualifier annotation type, or {@code null}
 * @param qualifier the qualifier annotation instance, or {@code null}
 * @param annotationMetadata annotations present on the field
 * @param isProvider whether the declared field is a {@code Provider} of {@code fieldType}
 */
public record FieldInjectionPoint(
        Factory<?> factory,
        String fieldName,
        Type fieldType,
        @Nullable Class<? extends Annotation> qualifierType,
        @Nullable Annotation qualifier,
        AnnotationMetadata annotationMetadata,
        boolean isProvider)
        implements InjectionPoint {

    /**
     * Creates a field injection point.
     *
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param qualifierType the marker qualifier annotation type, or {@code null}
     * @param qualifier the qualifier annotation instance, or {@code null}
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
     * Creates a field injection point.
     *
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param annotationMetadata annotations present on the field
     */
    public FieldInjectionPoint(
            Factory<?> factory, String fieldName, Type fieldType, AnnotationMetadata annotationMetadata) {
        this(factory, fieldName, fieldType, null, null, annotationMetadata, false);
    }

    /**
     * Creates a field injection point.
     *
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param qualifierType the marker qualifier annotation type
     * @param annotationMetadata annotations present on the field
     */
    public FieldInjectionPoint(
            Factory<?> factory,
            String fieldName,
            Type fieldType,
            Class<? extends Annotation> qualifierType,
            AnnotationMetadata annotationMetadata) {
        this(factory, fieldName, fieldType, qualifierType, null, annotationMetadata, false);
    }

    /**
     * Creates a field injection point.
     *
     * @param factory the factory owning this injection point
     * @param fieldName the field name
     * @param fieldType the field's declared type
     * @param qualifier the qualifier annotation instance
     * @param annotationMetadata annotations present on the field
     */
    public FieldInjectionPoint(
            Factory<?> factory,
            String fieldName,
            Type fieldType,
            Annotation qualifier,
            AnnotationMetadata annotationMetadata) {
        this(factory, fieldName, fieldType, null, qualifier, annotationMetadata, false);
    }

    /** {@return whether this field has a qualifier} */
    public boolean hasQualifier() {
        return qualifierType != null || qualifier != null;
    }

    /**
     * {@return the key for this field}
     *
     * @param <T> the field's dependency type
     */
    @SuppressWarnings("unchecked")
    public <T> Key<T> key() {
        return (Key<T>) (qualifier != null ? Key.of(fieldType, qualifier) : Key.of(fieldType, qualifierType));
    }
}
