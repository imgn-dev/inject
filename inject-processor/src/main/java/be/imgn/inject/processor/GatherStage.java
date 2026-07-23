package be.imgn.inject.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.IntoMap;
import be.imgn.inject.IntoSet;
import be.imgn.inject.Provides;

/** Stage 1: discovers injectable types and {@code @Provides} methods and builds their models. */
final class GatherStage {

    private final ModelFactory models;
    private final Diagnostics diagnostics;

    GatherStage(ModelFactory models, Diagnostics diagnostics) {
        this.models = models;
        this.diagnostics = diagnostics;
    }

    Gathered gather(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<TypeElement> beanTypes = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Inject.class)) {
            if (element.getEnclosingElement() instanceof TypeElement bean) {
                beanTypes.add(bean);
            }
        }
        for (TypeElement annotation : annotations) {
            if (annotation.getAnnotation(InjectingAnnotation.class) == null) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.FIELD
                        && element.getEnclosingElement() instanceof TypeElement bean) {
                    beanTypes.add(bean);
                }
            }
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(ExposeAs.class)) {
            if (element.getKind() == ElementKind.METHOD && element.getAnnotation(Provides.class) == null) {
                diagnostics.error("@ExposeAs on a method is only allowed together with @Provides.", element);
            }
        }
        Set<Element> markerElements = new LinkedHashSet<>();
        markerElements.addAll(roundEnv.getElementsAnnotatedWith(IntoSet.class));
        markerElements.addAll(roundEnv.getElementsAnnotatedWith(IntoMap.class));
        for (Element element : markerElements) {
            if (element.getKind() == ElementKind.METHOD && element.getAnnotation(Provides.class) == null) {
                diagnostics.error("@IntoSet/@IntoMap on a method requires @Provides.", element);
            }
        }

        Map<TypeElement, BeanModel> beans = new LinkedHashMap<>();
        Map<TypeElement, List<TypeElement>> edges = new LinkedHashMap<>();
        List<TypeElement> seeds = new ArrayList<>();
        Map<String, List<AssistedRecipe>> assistedRecipes = new LinkedHashMap<>();
        Set<String> assistedBeanNames = new LinkedHashSet<>();
        for (TypeElement bean : beanTypes) {
            if (!models.validateBean(bean)) {
                continue;
            }
            Optional<ExecutableElement> constructor = models.injectConstructor(bean);
            if (constructor.isPresent() && ModelFactory.isAssisted(constructor.get())) {
                // Assisted beans have no binding of their own: no BeanModel, no factory, no edges. They are
                // reachable only through a generated @AssistedFactory implementation (Task 2).
                models.validateAssistedRecipe(bean);
                String key = bean.getQualifiedName().toString();
                assistedRecipes
                        .computeIfAbsent(key, unused -> new ArrayList<>())
                        .add(new AssistedRecipe(bean, null, constructor.get()));
                assistedBeanNames.add(key);
                continue;
            }
            beans.put(bean, models.buildModel(bean));
            // Cycle edges exclude Provider-wrapped dependencies: a Provider is injected un-resolved, so it
            // legitimately breaks a cycle at runtime and must not be reported as one at compile time.
            edges.put(bean, models.dependencies(bean, false));
            seeds.addAll(models.dependencies(bean, true));
        }

        List<ProvidesModel> provides = gatherProvides(roundEnv, assistedRecipes);
        seeds.addAll(providesDependencies(roundEnv));
        return new Gathered(beans, provides, edges, seeds, assistedRecipes, assistedBeanNames);
    }

    private List<ProvidesModel> gatherProvides(
            RoundEnvironment roundEnv, Map<String, List<AssistedRecipe>> assistedRecipes) {
        Map<String, Integer> indices = new HashMap<>();
        List<ProvidesModel> result = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Provides.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            if (!(method.getEnclosingElement() instanceof TypeElement host)) {
                continue;
            }
            String key = host.getQualifiedName().toString() + "#" + method.getSimpleName();
            if (ModelFactory.isAssisted(method)) {
                // An assisted @Provides method has no binding of its own: skip factory generation, keep the
                // recipe for Task 2's @AssistedFactory link resolution.
                models.validateAssistedRecipe(method);
                assistedRecipes
                        .computeIfAbsent(key, unused -> new ArrayList<>())
                        .add(new AssistedRecipe(host, method, method));
                continue;
            }
            int index = indices.merge(key, 0, (existing, ignored) -> existing + 1);
            result.add(models.providesModel(method, host, index));
        }
        return result;
    }

    private List<TypeElement> providesDependencies(RoundEnvironment roundEnv) {
        List<TypeElement> dependencies = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Provides.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            if (ModelFactory.isAssisted(method)) {
                continue; // no generated factory calls it, so it needs no resolved dependencies
            }
            if (!method.getModifiers().contains(Modifier.STATIC)
                    && method.getEnclosingElement() instanceof TypeElement host) {
                dependencies.add(host);
            }
            models.addParameterDependencies(method, dependencies);
        }
        return dependencies;
    }
}
