package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

/**
 * An {@code @Inject} field to generate injection for.
 *
 * @param name the field name
 * @param rawType the erased field type (used as a {@code Class} literal)
 */
record FieldModel(String name, TypeName rawType) {}
