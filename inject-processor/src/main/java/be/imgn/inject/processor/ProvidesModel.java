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
 * @param scopeAnnotation the method's scoping annotation type, or {@code null} for prototype scope
 * @param qualifier a {@code new Qualifier(...)} expression for the produced key, or {@code null} if unqualified
 */
record ProvidesModel(
        String packageName,
        String factorySimpleName,
        TypeName producedType,
        ClassName hostType,
        String methodName,
        boolean isStatic,
        List<ParameterModel> methodParameters,
        @Nullable ClassName scopeAnnotation,
        @Nullable CodeBlock qualifier) {}
