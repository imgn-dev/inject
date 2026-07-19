package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * An {@code @Inject} field to generate injection for.
 *
 * @param name the field name
 * @param rawType the erased field type (used as a {@code Class} literal; the inner type when {@code isProvider})
 * @param qualifier the rendered qualifier, or {@code null} if unqualified
 * @param isProvider whether the declared field is a {@code Provider} of {@code rawType}
 */
record FieldModel(String name, TypeName rawType, @Nullable RenderedQualifier qualifier, boolean isProvider) {}
