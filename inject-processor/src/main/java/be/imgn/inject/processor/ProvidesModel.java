package be.imgn.inject.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import java.util.List;
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
 * @param definitionMetadata an {@code AnnotationMetadata} expression for the framework-related annotations declared on
 *     the {@code @Provides} method itself, exposed through {@code BeanDefinition}
 * @param scopeAnnotation the method's scoping annotation type, or {@code null} for prototype scope
 * @param qualifier the rendered qualifier applied to every exposed key, or {@code null}
 * @param exposedTypes the types the result is exposed as; empty means "use the factory's default single key"
 * @param contribution the collection contribution, or {@code null} when the bean binds under its own keys
 * @param producedKeyType the parameterized collection key type expression for a Set/Map-returning method, or
 *     {@code null}
 * @param isDefault whether the method is a {@code @Default} fallback recipe
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
        CodeBlock definitionMetadata,
        @Nullable ClassName scopeAnnotation,
        @Nullable RenderedQualifier qualifier,
        List<TypeName> exposedTypes,
        @Nullable ContributionModel contribution,
        @Nullable CodeBlock producedKeyType,
        boolean isDefault) {}
