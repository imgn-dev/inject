package be.imgn.inject.context;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The framework-related annotations captured at an injection point or provision definition: every annotation whose type
 * carries {@link be.imgn.inject.InjectingAnnotation}, {@link be.imgn.inject.QualifyingAnnotation}, or
 * {@link be.imgn.inject.ScopingAnnotation}.
 *
 * <p>Annotations are held as {@link AnnotationValue} data computed at compile time, so inspection needs no reflection:
 *
 * <pre>{@code
 * metadata.get(Named.class).flatMap(AnnotationValue::stringValue);      // by type
 * metadata.byStereotype(QualifyingAnnotation.class);                    // every qualifier at this site
 * }</pre>
 *
 * <p>At most one annotation per annotation type is held, matching the language rule for non-repeatable annotations.
 */
public final class AnnotationMetadata {
    private static final AnnotationMetadata EMPTY = new AnnotationMetadata(Map.of());

    private final Map<Class<? extends Annotation>, AnnotationValue> annotations;

    private AnnotationMetadata(Map<Class<? extends Annotation>, AnnotationValue> annotations) {
        this.annotations = annotations;
    }

    /**
     * Returns the shared empty metadata instance.
     *
     * @return the shared empty metadata instance
     */
    public static AnnotationMetadata empty() {
        return EMPTY;
    }

    /**
     * Returns metadata holding {@code annotations}, keyed by annotation type.
     *
     * @param annotations the framework-related annotations present at the site; later entries win for a repeated type
     * @return metadata holding {@code annotations}, keyed by annotation type
     */
    public static AnnotationMetadata of(AnnotationValue... annotations) {
        requireNonNull(annotations, "annotations");
        if (annotations.length == 0) {
            return EMPTY;
        }
        Map<Class<? extends Annotation>, AnnotationValue> byType = new LinkedHashMap<>();
        for (AnnotationValue annotation : annotations) {
            requireNonNull(annotation, "annotation");
            byType.put(annotation.annotationType(), annotation);
        }
        return new AnnotationMetadata(Map.copyOf(byType));
    }

    /**
     * Returns the annotation of {@code type} present at the site, if any.
     *
     * @param type the annotation type to look up
     * @return the annotation of {@code type} present at the site, if any
     */
    public Optional<AnnotationValue> get(Class<? extends Annotation> type) {
        requireNonNull(type, "type");
        return Optional.ofNullable(annotations.get(type));
    }

    /**
     * Returns whether an annotation of {@code type} is present at the site.
     *
     * @param type the annotation type to test
     * @return whether an annotation of {@code type} is present at the site
     */
    public boolean isPresent(Class<? extends Annotation> type) {
        requireNonNull(type, "type");
        return annotations.containsKey(type);
    }

    /**
     * Returns every annotation at the site whose type carries {@code stereotype}.
     *
     * @param stereotype the meta-annotation to filter by, such as {@code QualifyingAnnotation.class}
     * @return every annotation at the site whose type carries {@code stereotype}
     */
    public List<AnnotationValue> byStereotype(Class<? extends Annotation> stereotype) {
        requireNonNull(stereotype, "stereotype");
        return annotations.values().stream()
                .filter(annotation -> annotation.hasStereotype(stereotype))
                .toList();
    }

    /**
     * Returns whether any annotation at the site carries {@code stereotype}.
     *
     * @param stereotype the meta-annotation to test
     * @return whether any annotation at the site carries {@code stereotype}
     */
    public boolean hasStereotype(Class<? extends Annotation> stereotype) {
        requireNonNull(stereotype, "stereotype");
        return annotations.values().stream().anyMatch(annotation -> annotation.hasStereotype(stereotype));
    }

    /**
     * Returns the annotation types present at the site.
     *
     * @return the annotation types present at the site
     */
    public Set<Class<? extends Annotation>> types() {
        return annotations.keySet();
    }

    /**
     * Returns whether no framework-related annotation was present at the site.
     *
     * @return whether no framework-related annotation was present at the site
     */
    public boolean isEmpty() {
        return annotations.isEmpty();
    }

    @Override
    public String toString() {
        return "AnnotationMetadata" + annotations.values();
    }
}
