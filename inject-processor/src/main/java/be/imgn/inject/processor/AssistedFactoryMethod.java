package be.imgn.inject.processor;

import java.util.List;
import javax.lang.model.element.ExecutableElement;

/**
 * One resolved {@code @AssistedFactory} method.
 *
 * @param method the interface method
 * @param recipe the linked recipe
 * @param assistedArgumentOrder for the recipe's i-th assisted parameter, the factory-method parameter index feeding it
 */
record AssistedFactoryMethod(ExecutableElement method, AssistedRecipe recipe, List<Integer> assistedArgumentOrder) {}
