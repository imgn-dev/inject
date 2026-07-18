package be.imgn.inject.context;

/**
 * Placeholder for annotation information captured at an injection point. Currently only an empty instance is available;
 * richer accessors will be added when plugins need them.
 */
public final class AnnotationMetadata {
    private static final AnnotationMetadata EMPTY = new AnnotationMetadata();

    private AnnotationMetadata() {}

    /** {@return the shared empty metadata instance} */
    public static AnnotationMetadata empty() {
        return EMPTY;
    }
}
