package be.imgn.inject.processor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.jspecify.annotations.Nullable;

/**
 * A factory-only construction recipe: an {@code @Inject} constructor or {@code @Provides} method carrying
 * {@code @Assisted} parameters.
 *
 * @param owner the class declaring the recipe (the bean itself, or the {@code @Provides} host)
 * @param providesMethod the assisted {@code @Provides} method, or {@code null} for a constructor recipe
 * @param executable the recipe executable: the constructor, or {@code providesMethod}
 */
record AssistedRecipe(TypeElement owner, @Nullable ExecutableElement providesMethod, ExecutableElement executable) {}
