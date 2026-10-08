package be.imgn.inject;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A binding identity: a {@link Type} optionally combined with a qualifier annotation. Following Guice, a qualifier is
 * either a marker annotation type (no attributes, matched by type) or a concrete annotation instance (attributes
 * matter, matched by the annotation's {@code equals}). An unqualified key carries neither.
 *
 * @param <T> the bound type
 */
// T is a phantom parameter carried for call-site type-safety (getInstance/getProvider); it is intentionally
// not referenced by the class body, which stores the type as a java.lang.reflect.Type.
@SuppressWarnings("UnusedTypeParameter")
public final class Key<T> {
    private final Type type;
    private final @Nullable Class<? extends Annotation> annotationType;
    private final @Nullable Annotation annotation;

    private Key(Type type, @Nullable Class<? extends Annotation> annotationType, @Nullable Annotation annotation) {
        this.type = requireNonNull(type, "type");
        this.annotationType = annotationType;
        this.annotation = annotation;
    }

    /**
     * Returns an unqualified key for {@code type}.
     *
     * @param type the bound type
     * @param <T> the bound type
     * @return an unqualified key for {@code type}
     */
    public static <T> Key<T> of(Class<T> type) {
        return new Key<>(type, null, null);
    }

    /**
     * Returns a key for {@code type} qualified by a marker annotation type.
     *
     * @param type the bound type
     * @param qualifierType the marker qualifier annotation type
     * @param <T> the bound type
     * @return a key for {@code type} qualified by a marker annotation type
     */
    public static <T> Key<T> of(Class<T> type, Class<? extends Annotation> qualifierType) {
        return new Key<>(type, requireNonNull(qualifierType, "qualifierType"), null);
    }

    /**
     * Returns a key for {@code type} qualified by an annotation instance.
     *
     * @param type the bound type
     * @param qualifier the qualifier annotation instance (for example {@link Names#named})
     * @param <T> the bound type
     * @return a key for {@code type} qualified by an annotation instance
     */
    public static <T> Key<T> of(Class<T> type, Annotation qualifier) {
        requireNonNull(qualifier, "qualifier");
        return new Key<>(type, qualifier.annotationType(), qualifier);
    }

    /**
     * Returns an unqualified key for {@code type}.
     *
     * @param type the bound type literal
     * @param <T> the bound type
     * @return an unqualified key for {@code type}
     */
    public static <T> Key<T> of(TypeLiteral<T> type) {
        return new Key<>(requireNonNull(type, "type").getType(), null, null);
    }

    /**
     * Returns a key for {@code type} qualified by a marker annotation type.
     *
     * @param type the bound type literal
     * @param qualifierType the marker qualifier annotation type
     * @param <T> the bound type
     * @return a key for {@code type} qualified by a marker annotation type
     */
    public static <T> Key<T> of(TypeLiteral<T> type, Class<? extends Annotation> qualifierType) {
        return new Key<>(requireNonNull(type, "type").getType(), requireNonNull(qualifierType, "qualifierType"), null);
    }

    /**
     * Returns a key for {@code type} qualified by an annotation instance.
     *
     * @param type the bound type literal
     * @param qualifier the qualifier annotation instance
     * @param <T> the bound type
     * @return a key for {@code type} qualified by an annotation instance
     */
    public static <T> Key<T> of(TypeLiteral<T> type, Annotation qualifier) {
        requireNonNull(type, "type");
        requireNonNull(qualifier, "qualifier");
        return new Key<>(type.getType(), qualifier.annotationType(), qualifier);
    }

    /**
     * Returns an unqualified key for a general {@link Type}.
     *
     * @param type the bound type (may be parameterized)
     * @return an unqualified key for a general {@link Type}
     */
    public static Key<?> of(Type type) {
        return new Key<>(type, null, null);
    }

    /**
     * Returns a key for a general {@link Type} with an optional marker qualifier.
     *
     * @param type the bound type (may be parameterized)
     * @param qualifierType the marker qualifier annotation type, or {@code null} for none
     * @return a key for a general {@link Type} with an optional marker qualifier
     */
    public static Key<?> of(Type type, @Nullable Class<? extends Annotation> qualifierType) {
        return new Key<>(type, qualifierType, null);
    }

    /**
     * Returns a key for a general {@link Type} with an optional annotation-instance qualifier.
     *
     * @param type the bound type (may be parameterized)
     * @param qualifier the qualifier annotation instance, or {@code null} for none
     * @return a key for a general {@link Type} with an optional annotation-instance qualifier
     */
    public static Key<?> of(Type type, @Nullable Annotation qualifier) {
        return new Key<>(type, qualifier == null ? null : qualifier.annotationType(), qualifier);
    }

    /**
     * Returns the bound type.
     *
     * @return the bound type
     */
    public Type type() {
        return type;
    }

    /**
     * Returns the qualifier annotation type, or {@code null} if unqualified.
     *
     * @return the qualifier annotation type, or {@code null} if unqualified
     */
    public @Nullable Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    /**
     * Returns the qualifier annotation instance, or {@code null} for a marker or unqualified key.
     *
     * @return the qualifier annotation instance, or {@code null} for a marker or unqualified key
     */
    public @Nullable Annotation annotation() {
        return annotation;
    }

    /**
     * Returns whether this key carries a qualifier.
     *
     * @return whether this key carries a qualifier
     */
    public boolean hasQualifier() {
        return annotationType != null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Key<?> other
                && type.equals(other.type)
                && Objects.equals(annotationType, other.annotationType)
                && Objects.equals(annotation, other.annotation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, annotationType, annotation);
    }

    @Override
    public String toString() {
        if (annotation != null) {
            return "Key[" + type.getTypeName() + " " + annotation + "]";
        }
        if (annotationType != null) {
            return "Key[" + type.getTypeName() + " @" + annotationType.getName() + "]";
        }
        return "Key[" + type.getTypeName() + "]";
    }
}
