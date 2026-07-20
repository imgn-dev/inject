package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The framework-related annotations captured at an injection point or provision definition: every annotation that is
 * itself meta-annotated with {@link be.imgn.inject.InjectingAnnotation}, {@link be.imgn.inject.QualifyingAnnotation},
 * or {@link be.imgn.inject.ScopingAnnotation}.
 *
 * <p>Annotations are stored as instances, so attribute values are readable type-safely:
 *
 * <pre>{@code
 * metadata.get(Named.class).map(Named::value);
 * }</pre>
 *
 * <p>At most one annotation per annotation type is held, matching the language rule for non-repeatable annotations.
 */
public final class AnnotationMetadata {
    private static final AnnotationMetadata EMPTY = new AnnotationMetadata(Map.of());

    private final Map<Class<? extends Annotation>, Annotation> annotations;

    private AnnotationMetadata(Map<Class<? extends Annotation>, Annotation> annotations) {
        this.annotations = annotations;
    }

    /** {@return the shared empty metadata instance} */
    public static AnnotationMetadata empty() {
        return EMPTY;
    }

    /**
     * {@return metadata holding {@code annotations}, keyed by their annotation type}
     *
     * @param annotations the framework-related annotations present at the site
     */
    public static AnnotationMetadata of(Annotation... annotations) {
        requireNonNull(annotations, "annotations");
        if (annotations.length == 0) {
            return EMPTY;
        }
        Map<Class<? extends Annotation>, Annotation> byType = new LinkedHashMap<>();
        for (Annotation annotation : annotations) {
            requireNonNull(annotation, "annotation");
            byType.put(annotation.annotationType(), annotation);
        }
        return new AnnotationMetadata(Map.copyOf(byType));
    }

    /**
     * {@return the annotation of {@code type} present at the site, if any}
     *
     * @param type the annotation type to look up
     * @param <A> the annotation type
     */
    public <A extends Annotation> Optional<A> get(Class<A> type) {
        requireNonNull(type, "type");
        return Optional.ofNullable(type.cast(annotations.get(type)));
    }

    /**
     * {@return whether an annotation of {@code type} is present at the site}
     *
     * @param type the annotation type to test
     */
    public boolean isPresent(Class<? extends Annotation> type) {
        requireNonNull(type, "type");
        return annotations.containsKey(type);
    }

    /** {@return the annotation types present at the site} */
    public Set<Class<? extends Annotation>> types() {
        return annotations.keySet();
    }

    /** {@return whether no framework-related annotation was present at the site} */
    public boolean isEmpty() {
        return annotations.isEmpty();
    }

    @Override
    public String toString() {
        return "AnnotationMetadata" + annotations.values();
    }
}
