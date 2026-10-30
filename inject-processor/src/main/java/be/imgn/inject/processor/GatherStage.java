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
import java.util.HashSet;
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
import javax.lang.model.util.ElementFilter;

/** Stage 1: discovers injectable types and {@code @Provides} methods and builds their models. */
final class GatherStage {

    private final ProcessingContext context;
    private final ModelFactory models;
    private final Diagnostics diagnostics;
    // The link keys of the recipes gathered in any round, so a factory of a later round finds them.
    private final Set<String> knownRecipeKeys = new HashSet<>();
    // The @AssistedFactory interfaces waiting for their product's recipe, by qualified name: retried each round, and
    // reported after the last one. The stage keeps them itself, as the step processor would report them as deferred.
    private final Set<String> pendingFactories = new LinkedHashSet<>();

    GatherStage(ProcessingContext context, ModelFactory models, Diagnostics diagnostics) {
        this.context = context;
        this.models = models;
        this.diagnostics = diagnostics;
    }

    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength"})
    Gathered gather(AnnotatedElements round) {
        // Each bean type, with the elements of the round that make it one: given back if the bean is deferred.
        Map<TypeElement, Set<Element>> beanTypes = new LinkedHashMap<>();
        for (Element element : round.annotatedWith(Inject.class.getCanonicalName())) {
            MoreElements.enclosingType(element).ifPresent(bean -> addBean(beanTypes, bean, element));
        }
        for (Element element : round.annotatedWith(Intercepts.class.getCanonicalName())) {
            MoreElements.asTypeElement(element).ifPresent(bean -> addBean(beanTypes, bean, element));
        }
        for (Element element : round.withAnnotationsAnnotatedWith(InjectingAnnotation.class.getCanonicalName())) {
            if (element.getKind() == ElementKind.FIELD) {
                MoreElements.enclosingType(element).ifPresent(bean -> addBean(beanTypes, bean, element));
            }
        }
        for (Element element : round.withAnnotationsAnnotatedWith(InterceptingAnnotation.class.getCanonicalName())) {
            if (element.getKind().isDeclaredType()) {
                addBean(beanTypes, MoreElements.asType(element), element);
            } else if (element.getKind() == ElementKind.METHOD) {
                MoreElements.enclosingType(element).ifPresent(bean -> addBean(beanTypes, bean, element));
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
        Map<TypeElement, Set<Element>> deferred = new LinkedHashMap<>();
        for (TypeElement bean : beanTypes.keySet()) {
            if (ModelFactory.hasUnresolvedScope(bean)) {
                // The scope may come from a supertype another processor has yet to generate.
                deferred.put(bean, beanTypes.get(bean));
                continue;
            }
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
                // @PreDestroy needs a scope: the host of a scoped product destroys it when it closes.
                models.lifecycleCallbacks(bean, models.scope(bean).isPresent(), true);
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
        knownRecipeKeys.addAll(assistedRecipes.keySet());
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
                interceptionRejected,
                deferred);
    }

    private static void addBean(Map<TypeElement, Set<Element>> beanTypes, TypeElement bean, Element element) {
        beanTypes.computeIfAbsent(bean, unused -> new LinkedHashSet<>()).add(element);
    }

    /**
     * {@return {@code containerDependencies}'s resolved types paired with {@code site} for the direct-injection check}
     */
    private static List<AssistedReference> toAssistedReferences(Element site, List<TypeElement> containerDependencies) {
        return containerDependencies.stream()
                .map(type -> new AssistedReference(site, type.getQualifiedName().toString()))
                .toList();
    }

    /** {@return the round's {@code @AssistedFactory} interfaces and the pending ones, each with its factory methods} */
    private Map<TypeElement, List<ExecutableElement>> assistedFactoryInterfaces(AnnotatedElements round) {
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
        for (String name : pendingFactories) {
            TypeElement pending = context.elements().getTypeElement(name);
            if (pending != null && !byInterface.containsKey(pending)) {
                byInterface.put(pending, assistedFactoryMethods(pending));
            }
        }
        return byInterface;
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private List<AssistedFactoryModel> gatherAssistedFactories(
            AnnotatedElements round, Map<String, List<AssistedRecipe>> recipes) {
        Map<TypeElement, List<ExecutableElement>> byInterface = assistedFactoryInterfaces(round);
        AssistedMatcher matcher = new AssistedMatcher(models.types(), diagnostics);
        List<AssistedFactoryModel> factories = new ArrayList<>();
        for (Map.Entry<TypeElement, List<ExecutableElement>> entry : byInterface.entrySet()) {
            TypeElement factoryInterface = entry.getKey();
            String interfaceName = factoryInterface.getQualifiedName().toString();
            if (awaitsRecipe(entry.getValue(), recipes)) {
                // The product's constructor may name a type another processor has yet to generate: javac holds it
                // back until a later round.
                pendingFactories.add(interfaceName);
                continue;
            }
            pendingFactories.remove(interfaceName);
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
                Map<String, List<AssistedRecipe>> withFallback = withRecipeFromMembers(method, annotation, recipes);
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
     * {@return whether one of {@code methods} links to a source of this compilation whose recipe no round has gathered
     * yet}
     */
    private boolean awaitsRecipe(List<ExecutableElement> methods, Map<String, List<AssistedRecipe>> recipes) {
        for (ExecutableElement method : methods) {
            Optional<AssistedMatcher.Link> link = context.annotation(method, AssistedFactory.class.getCanonicalName())
                    .flatMap(annotation -> AssistedMatcher.link(method, annotation));
            if (link.isPresent()
                    && !recipes.containsKey(link.get().key())
                    && !knownRecipeKeys.contains(link.get().key())
                    && context.isFromSource(link.get().implementationElement())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports the {@code @AssistedFactory} interfaces still waiting for their product's recipe after the last round. A
     * product with a recipe-shaped member was held back as unresolved, which the step processor reports itself.
     */
    void reportPendingFactories() {
        AssistedMatcher matcher = new AssistedMatcher(models.types(), diagnostics);
        for (String name : pendingFactories) {
            TypeElement factoryInterface = context.elements().getTypeElement(name);
            if (factoryInterface == null) {
                continue;
            }
            for (ExecutableElement method : assistedFactoryMethods(factoryInterface)) {
                Optional<AnnotationReader> annotation =
                        context.annotation(method, AssistedFactory.class.getCanonicalName());
                if (annotation.isEmpty()) {
                    continue;
                }
                Map<String, List<AssistedRecipe>> members = new LinkedHashMap<>();
                AssistedMatcher.link(method, annotation.get()).ifPresent(link -> {
                    List<AssistedRecipe> recipe = recipeFromMembers(link.implementationElement(), link.recipeMethod());
                    if (!recipe.isEmpty()) {
                        members.put(link.key(), recipe);
                    }
                });
                matcher.resolve(method, annotation.get(), members);
            }
        }
        pendingFactories.clear();
    }

    private static List<ExecutableElement> assistedFactoryMethods(TypeElement factoryInterface) {
        return ElementFilter.methodsIn(factoryInterface.getEnclosedElements()).stream()
                .filter(method -> MoreElements.isAnnotatedWith(method, AssistedFactory.class))
                .toList();
    }

    /**
     * When {@code method}'s link key is absent from {@code recipes}, builds its {@link AssistedRecipe} directly from
     * the implementation class's members and merges it in: the class was compiled in a previous module, or its recipe
     * was gathered in an earlier round. Other misses on a source of this compilation are left alone: a genuinely
     * missing recipe is an error, not a fallback.
     */
    private Map<String, List<AssistedRecipe>> withRecipeFromMembers(
            ExecutableElement method, AnnotationReader annotation, Map<String, List<AssistedRecipe>> recipes) {
        Optional<AssistedMatcher.Link> link = AssistedMatcher.link(method, annotation);
        if (link.isEmpty()
                || recipes.containsKey(link.get().key())
                || (context.isFromSource(link.get().implementationElement())
                        && !knownRecipeKeys.contains(link.get().key()))) {
            return recipes;
        }
        List<AssistedRecipe> fallback =
                recipeFromMembers(link.get().implementationElement(), link.get().recipeMethod());
        if (fallback.isEmpty()) {
            return recipes;
        }
        Map<String, List<AssistedRecipe>> merged = new LinkedHashMap<>(recipes);
        merged.put(link.get().key(), fallback);
        return merged;
    }

    private List<AssistedRecipe> recipeFromMembers(TypeElement implementationElement, String recipeMethod) {
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
            result.add(models.providesModel(method, host));
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
