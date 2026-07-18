package be.imgn.inject.processor;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * A resolved constructor/method parameter to generate.
 *
 * @param name the parameter name
 * @param rawType the erased dependency type (used as a {@code Class} literal; the inner type when {@code isProvider})
 * @param qualifier a {@code new Qualifier(...)} expression, or {@code null} if unqualified
 * @param isProvider whether the declared parameter is a {@code Provider} of {@code rawType}
 */
record ParameterModel(
        String name, TypeName rawType, @Nullable CodeBlock qualifier, boolean isProvider) {}
