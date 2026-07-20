package be.imgn.inject.processor;

import java.util.List;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * The data needed to generate one {@code @Provides} factory.
 *
 * @param packageName the package the factory is emitted into (the host's package)
 * @param factorySimpleName the generated factory's simple name
 * @param producedType the type the method produces (the factory's type)
 * @param hostType the type declaring the {@code @Provides} method
 * @param methodName the {@code @Provides} method name
 * @param isStatic whether the method is static
 * @param methodParameters the method's parameters, in order
 * @param metadata an {@code AnnotationMetadata} expression for the provision's framework-related annotations, including
 *     those on the declaring type
 * @param scopeAnnotation the method's scoping annotation type, or {@code null} for prototype scope
 * @param qualifier the rendered qualifier applied to every exposed key, or {@code null}
 * @param exposedTypes the types the result is exposed as; empty means "use the factory's default single key"
 */
record ProvidesModel(
        String packageName,
        String factorySimpleName,
        TypeName producedType,
        ClassName hostType,
        String methodName,
        boolean isStatic,
        List<ParameterModel> methodParameters,
        CodeBlock metadata,
        @Nullable ClassName scopeAnnotation,
        @Nullable RenderedQualifier qualifier,
        List<TypeName> exposedTypes) {}
