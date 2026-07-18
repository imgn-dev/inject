package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

/**
 * A resolved constructor/method parameter to generate.
 *
 * @param name the parameter name
 * @param rawType the erased dependency type (used as a {@code Class} literal)
 */
record ParameterModel(String name, TypeName rawType) {}
