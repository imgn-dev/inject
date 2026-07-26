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

import be.imgn.inject.AssistedFactory;
import be.imgn.inject.Default;
import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.InterceptingAnnotation;
import be.imgn.inject.Intercepts;
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
        for (Element element : roundEnv.getElementsAnnotatedWith(Intercepts.class)) {
            if (element instanceof TypeElement interceptor) {
                beanTypes.add(interceptor);
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
        for (TypeElement annotation : annotations) {
            if (annotation.getAnnotation(InterceptingAnnotation.class) == null) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof TypeElement bean) {
                    beanTypes.add(bean);
                } else if (element.getKind() == ElementKind.METHOD
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

        for (Element element : roundEnv.getElementsAnnotatedWith(Default.class)) {
            if (element.getAnnotation(Provides.class) == null) {
                diagnostics.error("@Default requires @Provides.", element);
            } else if (element.getAnnotation(IntoSet.class) != null || element.getAnnotation(IntoMap.class) != null) {
                diagnostics.error("@Default cannot join a collection contribution (@IntoSet/@IntoMap).", element);
            } else if (element instanceof ExecutableElement method && ModelFactory.isAssisted(method)) {
                diagnostics.error("@Default cannot mark an assisted recipe.", element);
            }
        }

        Map<TypeElement, BeanModel> beans = new LinkedHashMap<>();
        Map<TypeElement, List<TypeElement>> edges = new LinkedHashMap<>();
        List<TypeElement> seeds = new ArrayList<>();
        Map<String, List<AssistedRecipe>> assistedRecipes = new LinkedHashMap<>();
        Set<String> assistedBeanNames = new LinkedHashSet<>();
        List<AssistedReference> assistedReferences = new ArrayList<>();
        Set<TypeElement> interceptionRejected = new LinkedHashSet<>();
        for (TypeElement bean : beanTypes) {
            if (!models.validateInterceptionShape(bean)) {
                interceptionRejected.add(bean);
                continue;
            }
            if (!models.validateBean(bean)) {
                continue;
            }
            Optional<ExecutableElement> constructor = models.injectConstructor(bean);
            models.validateNoMisplacedAssisted(bean);
            if (constructor.isPresent() && ModelFactory.isAssisted(constructor.get())) {
                // Assisted beans have no binding of their own: no BeanModel, no factory, no edges. They are
                // reachable only through a generated @AssistedFactory implementation.
                models.validateAssistedRecipe(bean);
                models.lifecycleCallbacks(bean, false, true); // validate: @PreDestroy on assisted -> error
                String key = bean.getQualifiedName().toString();
                assistedRecipes
                        .computeIfAbsent(key, unused -> new ArrayList<>())
                        .add(new AssistedRecipe(bean, null, constructor.get()));
                assistedBeanNames.add(key);
                List<TypeElement> containerParams = models.containerDependencies(constructor.get());
                seeds.addAll(containerParams);
                assistedReferences.addAll(toAssistedReferences(constructor.get(), containerParams));
                continue;
            }
            beans.put(bean, models.buildModel(bean));
            // Cycle edges exclude Provider-wrapped dependencies: a Provider is injected un-resolved, so it
            // legitimately breaks a cycle at runtime and must not be reported as one at compile time.
            edges.put(bean, models.dependencies(bean, false));
            seeds.addAll(models.dependencies(bean, true));
        }

        List<ProvidesModel> provides = gatherProvides(roundEnv, assistedRecipes, seeds, assistedReferences);
        seeds.addAll(providesDependencies(roundEnv));
        List<AssistedFactoryModel> assistedFactories = gatherAssistedFactories(roundEnv, assistedRecipes);
        return new Gathered(
                beans,
                provides,
                edges,
                seeds,
                assistedRecipes,
                assistedBeanNames,
                assistedFactories,
                assistedReferences,
                interceptionRejected);
    }

    /**
     * {@return {@code containerDependencies}'s resolved types paired with {@code site} for the direct-injection check}
     */
    private static List<AssistedReference> toAssistedReferences(Element site, List<TypeElement> containerDependencies) {
        return containerDependencies.stream()
                .map(type -> new AssistedReference(site, type.getQualifiedName().toString()))
                .toList();
    }

    private List<AssistedFactoryModel> gatherAssistedFactories(
            RoundEnvironment roundEnv, Map<String, List<AssistedRecipe>> recipes) {
        Map<TypeElement, List<ExecutableElement>> byInterface = new LinkedHashMap<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(AssistedFactory.class)) {
            ExecutableElement method = (ExecutableElement) element;
            Element enclosingElement = method.getEnclosingElement();
            if (!(enclosingElement instanceof TypeElement enclosing) || enclosing.getKind() != ElementKind.INTERFACE) {
                diagnostics.error("The @AssistedFactory annotation belongs on an interface method.", method);
                continue;
            }
            byInterface.computeIfAbsent(enclosing, unused -> new ArrayList<>()).add(method);
        }
        AssistedMatcher matcher = new AssistedMatcher(models.types(), diagnostics);
        List<AssistedFactoryModel> factories = new ArrayList<>();
        for (Map.Entry<TypeElement, List<ExecutableElement>> entry : byInterface.entrySet()) {
            TypeElement factoryInterface = entry.getKey();
            Set<ExecutableElement> annotatedMethods = new LinkedHashSet<>(entry.getValue());
            boolean valid = true;
            for (Element enclosed : factoryInterface.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD
                        && enclosed.getModifiers().contains(Modifier.ABSTRACT)
                        && !annotatedMethods.contains(enclosed)) {
                    diagnostics.error(
                            enclosed.getSimpleName() + " has no @AssistedFactory and cannot be implemented.", enclosed);
                    valid = false;
                }
            }
            if (!valid) {
                continue;
            }
            List<AssistedFactoryMethod> methods = new ArrayList<>();
            for (ExecutableElement method : entry.getValue()) {
                AssistedFactory annotation = method.getAnnotation(AssistedFactory.class);
                if (annotation == null) {
                    continue;
                }
                Map<String, List<AssistedRecipe>> withFallback =
                        withCrossModuleFallback(method, annotation, recipes, roundEnv);
                Optional<AssistedFactoryMethod> resolved = matcher.resolve(method, annotation, withFallback);
                if (resolved.isEmpty()) {
                    valid = false;
                    continue;
                }
                methods.add(resolved.get());
            }
            if (valid) {
                factories.add(new AssistedFactoryModel(factoryInterface, List.copyOf(methods)));
            }
        }
        return List.copyOf(factories);
    }

    /**
     * Cross-module recipes: when {@code method}'s link key is absent from {@code recipes} because the implementation
     * class was compiled in a previous module (not part of the current round), builds its {@link AssistedRecipe}
     * directly from the class's members and merges it in. In-round misses are left alone: a genuinely missing recipe is
     * an error, not a fallback.
     */
    private Map<String, List<AssistedRecipe>> withCrossModuleFallback(
            ExecutableElement method,
            AssistedFactory annotation,
            Map<String, List<AssistedRecipe>> recipes,
            RoundEnvironment roundEnv) {
        Optional<AssistedMatcher.Link> link = AssistedMatcher.link(method, annotation);
        if (link.isEmpty()
                || recipes.containsKey(link.get().key())
                || isInRound(link.get().implementationElement(), roundEnv)) {
            return recipes;
        }
        List<AssistedRecipe> fallback =
                crossModuleRecipe(link.get().implementationElement(), link.get().recipeMethod());
        if (fallback.isEmpty()) {
            return recipes;
        }
        Map<String, List<AssistedRecipe>> merged = new LinkedHashMap<>(recipes);
        merged.put(link.get().key(), fallback);
        return merged;
    }

    private List<AssistedRecipe> crossModuleRecipe(TypeElement implementationElement, String recipeMethod) {
        List<AssistedRecipe> result = new ArrayList<>();
        for (Element enclosed : implementationElement.getEnclosedElements()) {
            if (recipeMethod.isEmpty()) {
                if (enclosed.getKind() == ElementKind.CONSTRUCTOR
                        && enclosed.getAnnotation(Inject.class) != null
                        && ModelFactory.isAssisted((ExecutableElement) enclosed)) {
                    result.add(new AssistedRecipe(implementationElement, null, (ExecutableElement) enclosed));
                }
            } else if (enclosed.getKind() == ElementKind.METHOD
                    && enclosed.getSimpleName().contentEquals(recipeMethod)
                    && enclosed.getAnnotation(Provides.class) != null
                    && ModelFactory.isAssisted((ExecutableElement) enclosed)) {
                result.add(new AssistedRecipe(
                        implementationElement, (ExecutableElement) enclosed, (ExecutableElement) enclosed));
            }
        }
        return result;
    }

    /** {@return whether {@code type} is one of the sources in the current compilation round} */
    private static boolean isInRound(TypeElement type, RoundEnvironment roundEnv) {
        Element top = type;
        while (top.getEnclosingElement() instanceof TypeElement enclosing) {
            top = enclosing;
        }
        return roundEnv.getRootElements().contains(top);
    }

    private List<ProvidesModel> gatherProvides(
            RoundEnvironment roundEnv,
            Map<String, List<AssistedRecipe>> assistedRecipes,
            List<TypeElement> seeds,
            List<AssistedReference> assistedReferences) {
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
                // recipe for @AssistedFactory link resolution.
                models.validateAssistedRecipe(method);
                assistedRecipes
                        .computeIfAbsent(key, unused -> new ArrayList<>())
                        .add(new AssistedRecipe(host, method, method));
                List<TypeElement> containerParams = models.containerDependencies(method);
                seeds.addAll(containerParams);
                assistedReferences.addAll(toAssistedReferences(method, containerParams));
                if (!method.getModifiers().contains(Modifier.STATIC)) {
                    seeds.add(host);
                }
                continue;
            }
            int index = indices.merge(key, 0, (existing, ignored) -> existing + 1);
            result.add(models.providesModel(method, host, index));
            assistedReferences.addAll(toAssistedReferences(method, models.containerDependencies(method)));
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
