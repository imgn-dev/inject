package be.imgn.inject.processor;

import com.palantir.javapoet.CodeBlock;

import org.jspecify.annotations.Nullable;

/**
 * An {@code @Inject} field to generate injection for.
 *
 * @param name the field name
 * @param type the dependency's type expression: a class literal, or a {@code Types.setOf}/{@code Types.mapOf} call for
 *     collections; the inner type's expression when {@code isProvider}
 * @param qualifier the rendered qualifier, or {@code null} if unqualified
 * @param isProvider whether the declared field is a {@code Provider} of {@code type}
 * @param metadata an {@code AnnotationMetadata} expression for the field's framework-related annotations
 */
record FieldModel(
        String name, CodeBlock type, @Nullable RenderedQualifier qualifier, boolean isProvider, CodeBlock metadata) {}
