package be.imgn.inject;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies a binding beyond its type. Identity is the qualifier annotation type together with its attribute values,
 * so {@code @Named("db")} and {@code @Named("cache")} are distinct. Marker qualifiers carry no attributes and match by
 * type alone.
 *
 * <p>Attribute values must be value-based (primitives, {@code String}, {@code Class}, enums, or {@code List}s of
 * these); array-valued attributes must be supplied as {@code List}s so equality is by value.
 */
public final class Qualifier {
    private final Class<? extends Annotation> annotationType;
    private final Map<String, Object> attributes;

    /**
     * @param annotationType the qualifier annotation type
     * @param attributes the qualifier's attribute values, by attribute name
     */
    public Qualifier(Class<? extends Annotation> annotationType, Map<String, Object> attributes) {
        this.annotationType = Objects.requireNonNull(annotationType, "annotationType");
        this.attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * {@return a marker qualifier with no attributes}
     *
     * @param annotationType the qualifier annotation type
     */
    public static Qualifier of(Class<? extends Annotation> annotationType) {
        return new Qualifier(annotationType, Map.of());
    }

    /** {@return the qualifier annotation type} */
    public Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    /** {@return the qualifier's attribute values, by attribute name} */
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Qualifier other
                && annotationType.equals(other.annotationType)
                && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(annotationType, attributes);
    }

    @Override
    public String toString() {
        return "@" + annotationType.getSimpleName() + attributes;
    }
}
