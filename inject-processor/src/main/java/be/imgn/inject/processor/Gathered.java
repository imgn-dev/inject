package be.imgn.inject.processor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;

/**
 * The output of the gather stage: the models to generate plus the dependency graph the plan stage needs.
 *
 * @param beans the bean factories to generate, keyed by bean type (mutable: the plan stage adds default factories)
 * @param provides the {@code @Provides} factories to generate
 * @param edges the bean dependency graph used for cycle detection (mutable)
 * @param seeds every dependency type referenced, used to plan default factories
 * @param assistedRecipes factory-only construction recipes, keyed by owner qualified name (constructor recipes) or
 *     {@code owner qualified name + "#" + method name} (provides recipes); more than one entry under a key is an
 *     ambiguous set of assisted {@code @Provides} overloads
 * @param assistedBeanNames qualified names of in-compilation beans reachable only through an {@code @AssistedFactory},
 *     not by direct injection
 * @param assistedFactories the resolved {@code @AssistedFactory} interfaces to implement
 * @param assistedReferences container-resolved parameter sites (a {@code @Provides} method parameter, or another
 *     assisted recipe's container parameter) whose declared type may be an assisted bean, checked by the plan stage
 * @param interceptionRejected bean types rejected at the interception shape gate, so the plan stage does not resurrect
 *     them as default factories and re-report the same shape error
 */
record Gathered(
        Map<TypeElement, BeanModel> beans,
        List<ProvidesModel> provides,
        Map<TypeElement, List<TypeElement>> edges,
        List<TypeElement> seeds,
        Map<String, List<AssistedRecipe>> assistedRecipes,
        Set<String> assistedBeanNames,
        List<AssistedFactoryModel> assistedFactories,
        List<AssistedReference> assistedReferences,
        Set<TypeElement> interceptionRejected) {}
