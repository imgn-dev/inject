package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One annotation captured at compile time: its type, its member values, and the framework stereotypes it carries.
 *
 * <p>Values are held as data rather than as an annotation instance, so reading them needs neither reflection nor a
 * generated implementation class. Member values are value-based: primitives (boxed), {@code String}, {@code Class},
 * enum constants, or {@code List}s of these for array-valued members.
 *
 * <p>A <em>stereotype</em> is a meta-annotation on the annotation type — here always one of
 * {@link be.imgn.inject.InjectingAnnotation}, {@link be.imgn.inject.QualifyingAnnotation}, or
 * {@link be.imgn.inject.ScopingAnnotation}.
 */
public final class AnnotationValue {
    private final Class<? extends Annotation> annotationType;
    private final Map<String, Object> values;
    private final Set<Class<? extends Annotation>> stereotypes;

    private AnnotationValue(
            Class<? extends Annotation> annotationType,
            Map<String, Object> values,
            Set<Class<? extends Annotation>> stereotypes) {
        this.annotationType = annotationType;
        this.values = values;
        this.stereotypes = stereotypes;
    }

    /**
     * Returns an annotation value.
     *
     * @param annotationType the annotation type
     * @param values the member values, by member name
     * @param stereotypes the framework meta-annotations present on {@code annotationType}
     * @return an annotation value
     */
    public static AnnotationValue of(
            Class<? extends Annotation> annotationType,
            Map<String, Object> values,
            Set<Class<? extends Annotation>> stereotypes) {
        return new AnnotationValue(
                requireNonNull(annotationType, "annotationType"),
                Map.copyOf(requireNonNull(values, "values")),
                Set.copyOf(requireNonNull(stereotypes, "stereotypes")));
    }

    /**
     * Returns the annotation type.
     *
     * @return the annotation type
     */
    public Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    /**
     * Returns the member values, by member name.
     *
     * @return the member values, by member name
     */
    public Map<String, Object> values() {
        return values;
    }

    /**
     * Returns the framework meta-annotations present on this annotation's type.
     *
     * @return the framework meta-annotations present on this annotation's type
     */
    public Set<Class<? extends Annotation>> stereotypes() {
        return stereotypes;
    }

    /**
     * Returns whether this annotation's type carries {@code stereotype}.
     *
     * @param stereotype the meta-annotation to test
     * @return whether this annotation's type carries {@code stereotype}
     */
    public boolean hasStereotype(Class<? extends Annotation> stereotype) {
        requireNonNull(stereotype, "stereotype");
        return stereotypes.contains(stereotype);
    }

    /**
     * Returns the value of {@code member}, converted to {@code type}, if present and assignable.
     *
     * @param member the member name
     * @param type the expected value type
     * @param <T> the expected value type
     * @return the value of {@code member}, converted to {@code type}, if present and assignable
     */
    public <T> Optional<T> get(String member, Class<T> type) {
        requireNonNull(member, "member");
        requireNonNull(type, "type");
        Object value = values.get(member);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    /**
     * Returns the {@code String} value of {@code member}, if present.
     *
     * @param member the member name
     * @return the {@code String} value of {@code member}, if present
     */
    public Optional<String> stringValue(String member) {
        return get(member, String.class);
    }

    /**
     * Returns the {@code value} member as a {@code String}, if present.
     *
     * @return the {@code value} member as a {@code String}, if present
     */
    public Optional<String> stringValue() {
        return stringValue("value");
    }

    /**
     * Returns the {@code int} value of {@code member}, if present.
     *
     * @param member the member name
     * @return the {@code int} value of {@code member}, if present
     */
    public Optional<Integer> intValue(String member) {
        return get(member, Integer.class);
    }

    /**
     * Returns the {@code boolean} value of {@code member}, if present.
     *
     * @param member the member name
     * @return the {@code boolean} value of {@code member}, if present
     */
    public Optional<Boolean> booleanValue(String member) {
        return get(member, Boolean.class);
    }

    /**
     * Returns the {@code Class} value of {@code member}, if present.
     *
     * @param member the member name
     * @return the {@code Class} value of {@code member}, if present
     */
    public Optional<Class<?>> classValue(String member) {
        Object value = values.get(member);
        return value instanceof Class<?> type ? Optional.of(type) : Optional.empty();
    }

    /**
     * Returns the enum value of {@code member}, if present and of {@code enumType}.
     *
     * @param member the member name
     * @param enumType the expected enum type
     * @param <E> the expected enum type
     * @return the enum value of {@code member}, if present and of {@code enumType}
     */
    public <E extends Enum<E>> Optional<E> enumValue(String member, Class<E> enumType) {
        return get(member, enumType);
    }

    /**
     * Returns the array-valued {@code member} as a list of {@code type}, empty when absent.
     *
     * @param member the member name
     * @param type the expected element type
     * @param <T> the expected element type
     * @return the array-valued {@code member} as a list of {@code type}, empty when absent
     */
    public <T> List<T> listValue(String member, Class<T> type) {
        requireNonNull(member, "member");
        requireNonNull(type, "type");
        if (!(values.get(member) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AnnotationValue other
                && annotationType.equals(other.annotationType)
                && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return annotationType.hashCode() * 31 + values.hashCode();
    }

    @Override
    public String toString() {
        return "@" + annotationType.getSimpleName() + (values.isEmpty() ? "" : values.toString());
    }
}
