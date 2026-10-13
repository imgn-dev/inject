package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.CodeBlock;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link RenderedQualifier} into the two-slot ({@code qualifierType}, {@code qualifier}) constructor form.
 */
final class Qualifiers {

    private Qualifiers() {}

    /** {@return the marker-annotation-type argument: the qualifier's expression when a marker, else {@code null}} */
    static CodeBlock markerArgument(@Nullable RenderedQualifier qualifier) {
        return qualifier != null && qualifier.marker() ? qualifier.expression() : CodeBlock.of("null");
    }

    /** {@return the annotation-instance argument: the qualifier's expression when an instance, else {@code null}} */
    static CodeBlock instanceArgument(@Nullable RenderedQualifier qualifier) {
        return qualifier != null && !qualifier.marker() ? qualifier.expression() : CodeBlock.of("null");
    }
}
