package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * A bean's collection contribution to generate.
 *
 * @param elementType the collection's element (or map value) type
 * @param mapKey the map key for an {@code @IntoMap} contribution, or {@code null} for {@code @IntoSet}
 */
record ContributionModel(TypeName elementType, @Nullable String mapKey) {}
