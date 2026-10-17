package be.imgn.inject.processor;

import be.imgn.gaia.processing.AnnotatedElements;
import be.imgn.gaia.processing.AnnotationReader;
import be.imgn.gaia.processing.Diagnostics;
import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.ProcessingContext;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/** Stage 1: discovers injectable types and {@code @Provides} methods and builds their models. */
final class GatherStage {

    private final ProcessingContext context;
    private final ModelFactory models;
    private final Diagnostics diagnostics;

    GatherStage(ProcessingContext context, ModelFactory models, Diagnostics diagnostics) {
        this.context = context;
        this.models = models;
        this.diagnostics = diagnostics;
    }

    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength"})
    Gathered gather(AnnotatedElements round) {
        Set<TypeElement> beanTypes = new LinkedHashSet<>();
        for (Element element : round.annotatedWith(Inject.class.getCanonicalName())) {
            MoreElements.enclosingType(element).ifPresent(beanTypes::add);
        }
        for (Element element : round.annotatedWith(Intercepts.class.getCanonicalName())) {
            MoreElements.asTypeElement(element).ifPresent(beanTypes::add);
        }
        for (Element element : round.withAnnotationsAnnotatedWith(InjectingAnnotation.class.getCanonicalName())) {
            if (element.getKind() == ElementKind.FIELD) {
                MoreElements.enclosingType(element).ifPresent(beanTypes::add);
            }
        }
        for (Element element : round.withAnnotationsAnnotatedWith(InterceptingAnnotation.class.getCanonicalName())) {
            if (element.getKind().isDeclaredType()) {
                beanTypes.add(MoreElements.asType(element));
            } else if (element.getKind() == ElementKind.METHOD) {
                MoreElements.enclosingType(element).ifPresent(beanTypes::add);
            }
        }
        for (Element element : round.annotatedWith(ExposeAs.class.getCanonicalName())) {
            if (element.getKind() == ElementKind.METHOD && !MoreElements.isAnnotatedWith(element, Provides.class)) {
                diagnostics.on(element).error("@ExposeAs on a method is only allowed together with @Provides.");
            }
        }
        Set<Element> markerElements = new LinkedHashSet<>();
        markerElements.addAll(round.annotatedWith(IntoSet.class.getCanonicalName()));
        markerElements.addAll(round.annotatedWith(IntoMap.class.getCanonicalName()));
        for (Element element : markerElements) {
            if (element.getKind() == ElementKind.METHOD && !MoreElements.isAnnotatedWith(element, Provides.class)) {
                diagnostics.on(element).error("@IntoSet/@IntoMap on a method requires @Provides.");
            }
        }

        for (Element element : round.annotatedWith(Default.class.getCanonicalName())) {
            if (!MoreElements.isAnnotatedWith(element, Provides.class)) {
                diagnostics.on(element).error("@Default requires @Provides.");
            } else if (MoreElements.isAnnotatedWith(element, IntoSet.class)
                    || MoreElements.isAnnotatedWith(element, IntoMap.class)) {
                diagnostics.on(element).error("@Default cannot join a collection contribution (@IntoSet/@IntoMap).");
            } else if (element.getKind().isExecutable()
                    && ModelFactory.isAssisted(MoreElements.asExecutable(element))) {
                diagnostics.on(element).error("@Default cannot mark an assisted recipe.");
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
                // Validate: @PreDestroy on assisted -> error
                models.lifecycleCallbacks(bean, false, true);
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

        List<ProvidesModel> provides = gatherProvides(round, assistedRecipes, seeds, assistedReferences);
        seeds.addAll(providesDependencies(round));
        List<AssistedFactoryModel> assistedFactories = gatherAssistedFactories(round, assistedRecipes);
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

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private List<AssistedFactoryModel> gatherAssistedFactories(
            AnnotatedElements round, Map<String, List<AssistedRecipe>> recipes) {
        Map<TypeElement, List<ExecutableElement>> byInterface = new LinkedHashMap<>();
        for (Element element : round.annotatedWith(AssistedFactory.class.getCanonicalName())) {
            ExecutableElement method = MoreElements.asExecutable(element);
            Optional<TypeElement> enclosing = MoreElements.enclosingType(method);
            if (enclosing.isEmpty() || enclosing.get().getKind() != ElementKind.INTERFACE) {
                diagnostics.on(method).error("The @AssistedFactory annotation belongs on an interface method.");
                continue;
            }
            byInterface
                    .computeIfAbsent(enclosing.get(), unused -> new ArrayList<>())
                    .add(method);
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
                    diagnostics
                            .on(enclosed)
                            .error(enclosed.getSimpleName() + " has no @AssistedFactory and cannot be implemented.");
                    valid = false;
                }
            }
            if (!valid) {
                continue;
            }
            List<AssistedFactoryMethod> methods = new ArrayList<>();
            for (ExecutableElement method : entry.getValue()) {
                Optional<AnnotationReader> found = context.annotation(method, AssistedFactory.class.getCanonicalName());
                if (found.isEmpty()) {
                    continue;
                }
                AnnotationReader annotation = found.get();
                Map<String, List<AssistedRecipe>> withFallback = withCrossModuleFallback(method, annotation, recipes);
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
     * class was compiled in a previous module (not from a source of this compilation), builds its
     * {@link AssistedRecipe} directly from the class's members and merges it in. Misses on a source of this compilation
     * are left alone: a genuinely missing recipe is an error, not a fallback.
     */
    private Map<String, List<AssistedRecipe>> withCrossModuleFallback(
            ExecutableElement method, AnnotationReader annotation, Map<String, List<AssistedRecipe>> recipes) {
        Optional<AssistedMatcher.Link> link = AssistedMatcher.link(method, annotation);
        if (link.isEmpty()
                || recipes.containsKey(link.get().key())
                || context.isFromSource(link.get().implementationElement())) {
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
                        && MoreElements.isAnnotatedWith(enclosed, Inject.class)
                        && ModelFactory.isAssisted(MoreElements.asExecutable(enclosed))) {
                    result.add(new AssistedRecipe(implementationElement, null, MoreElements.asExecutable(enclosed)));
                }
            } else if (enclosed.getKind() == ElementKind.METHOD
                    && enclosed.getSimpleName().contentEquals(recipeMethod)
                    && MoreElements.isAnnotatedWith(enclosed, Provides.class)
                    && ModelFactory.isAssisted(MoreElements.asExecutable(enclosed))) {
                result.add(new AssistedRecipe(
                        implementationElement,
                        MoreElements.asExecutable(enclosed),
                        MoreElements.asExecutable(enclosed)));
            }
        }
        return result;
    }

    private List<ProvidesModel> gatherProvides(
            AnnotatedElements round,
            Map<String, List<AssistedRecipe>> assistedRecipes,
            List<TypeElement> seeds,
            List<AssistedReference> assistedReferences) {
        Map<String, Integer> indices = new HashMap<>();
        List<ProvidesModel> result = new ArrayList<>();
        for (Element element : round.annotatedWith(Provides.class.getCanonicalName())) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = MoreElements.asExecutable(element);
            Optional<TypeElement> enclosingType = MoreElements.enclosingType(method);
            if (enclosingType.isEmpty()) {
                continue;
            }
            TypeElement host = enclosingType.get();
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

    private List<TypeElement> providesDependencies(AnnotatedElements round) {
        List<TypeElement> dependencies = new ArrayList<>();
        for (Element element : round.annotatedWith(Provides.class.getCanonicalName())) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = MoreElements.asExecutable(element);
            if (ModelFactory.isAssisted(method)) {
                // No generated factory calls it, so it needs no resolved dependencies
                continue;
            }
            if (!method.getModifiers().contains(Modifier.STATIC)) {
                MoreElements.enclosingType(method).ifPresent(dependencies::add);
            }
            models.addParameterDependencies(method, dependencies);
        }
        return dependencies;
    }
}
