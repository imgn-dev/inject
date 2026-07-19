package be.imgn.inject.processor;

import java.util.List;
import java.util.Map;
import javax.lang.model.element.TypeElement;

/**
 * The output of the gather stage: the models to generate plus the dependency graph the plan stage needs.
 *
 * @param beans the bean factories to generate, keyed by bean type (mutable: the plan stage adds default factories)
 * @param provides the {@code @Provides} factories to generate
 * @param edges the bean dependency graph used for cycle detection (mutable)
 * @param seeds every dependency type referenced, used to plan default factories
 */
record Gathered(
        Map<TypeElement, BeanModel> beans,
        List<ProvidesModel> provides,
        Map<TypeElement, List<TypeElement>> edges,
        List<TypeElement> seeds) {}
