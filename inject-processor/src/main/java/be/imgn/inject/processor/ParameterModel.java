package be.imgn.inject.processor;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import org.jspecify.annotations.Nullable;

/**
 * A resolved constructor/method parameter to generate.
 *
 * @param name the parameter name
 * @param type the dependency's type expression: a class literal, or a {@code Types.setOf}/{@code Types.mapOf} call for
 *     collections; the inner type's expression when {@code isProvider}
 * @param typeArgument the explicit type argument of the resolution call: the dependency type, boxed, or its erasure
 *     when the generated factory cannot name it
 * @param erasure the dependency type's erasure, passed to the resolution call as a class literal
 * @param qualifier the rendered qualifier, or {@code null} if unqualified
 * @param isProvider whether the declared parameter is a {@code Provider} of {@code type}
 * @param pluginInitializer a plugin-supplied argument expression (for an {@code @InjectingAnnotation} parameter), or
 *     {@code null} when the argument is resolved from the context
 * @param metadata an {@code AnnotationMetadata} expression for the parameter's framework-related annotations
 */
record ParameterModel(
        String name,
        CodeBlock type,
        TypeName typeArgument,
        TypeName erasure,
        @Nullable RenderedQualifier qualifier,
        boolean isProvider,
        @Nullable CodeBlock pluginInitializer,
        CodeBlock metadata) {}
