package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.gaia.processing.source.TypeName;
import org.jspecify.annotations.Nullable;

/**
 * An {@code @Inject} field to generate injection for.
 *
 * @param name the field name
 * @param type the dependency's type expression: a class literal, or a {@code Types.setOf}/{@code Types.mapOf} call for
 *     collections; the inner type's expression when {@code isProvider}
 * @param typeArgument the explicit type argument of the resolution call: the dependency type, boxed, or its erasure
 *     when the generated factory cannot name it
 * @param erasure the dependency type's erasure, passed to the resolution call as a class literal
 * @param qualifier the rendered qualifier, or {@code null} if unqualified
 * @param isProvider whether the declared field is a {@code Provider} of {@code type}
 * @param metadata an {@code AnnotationMetadata} expression for the field's framework-related annotations
 */
record FieldModel(
        String name,
        CodeBlock type,
        TypeName typeArgument,
        TypeName erasure,
        @Nullable RenderedQualifier qualifier,
        boolean isProvider,
        CodeBlock metadata) {}
