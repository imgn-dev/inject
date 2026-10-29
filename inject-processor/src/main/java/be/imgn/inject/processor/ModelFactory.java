package be.imgn.inject.processor;

import be.imgn.gaia.processing.AnnotationReader;
import be.imgn.gaia.processing.Diagnostics;
import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.MoreTypes;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.gaia.processing.source.TypeName;
import be.imgn.gaia.processing.source.TypeVariableName;
import be.imgn.inject.Assisted;
import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.Default;
import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Injector;
import be.imgn.inject.InterceptingAnnotation;
import be.imgn.inject.Intercepts;
import be.imgn.inject.IntoMap;
import be.imgn.inject.IntoSet;
import be.imgn.inject.PostConstruct;
import be.imgn.inject.PreDestroy;
import be.imgn.inject.Provider;
import be.imgn.inject.Provides;
import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;
import java.io.Closeable;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.jspecify.annotations.Nullable;

/**
 * Translates {@code javax.lang.model} elements into the generator's models ({@link BeanModel}, {@link ProvidesModel})
 * and answers structural questions about them (dependencies, accessibility, exposed types). Shared by the gather and
 * plan stages.
 */
final class ModelFactory {

    private static final String PROVIDER = Provider.class.getCanonicalName();
    private static final String SET = Set.class.getName();
    private static final String MAP = Map.class.getName();
    private static final String INJECTOR = Injector.class.getCanonicalName();
    private static final String CONTEXT = Context.class.getCanonicalName();
    private static final String FACTORY = Factory.class.getCanonicalName();
    private static final ClassName TYPES = ClassName.of("be.imgn.inject", "Types");

    // Types never exposed by the default (no-@ExposeAs) supertype closure: universal supertypes and common marker
    // interfaces that would collide across unrelated beans. @ExposeAs can still expose these explicitly.
    private static final Set<String> EXCLUDED_DEFAULT_EXPOSURES = Set.of(
            Object.class.getName(),
            Serializable.class.getName(),
            Cloneable.class.getName(),
            Comparable.class.getName(),
            Runnable.class.getName(),
            AutoCloseable.class.getName(),
            Closeable.class.getName(),
            Iterable.class.getName(),
            EventListener.class.getName(),
            Injector.class.getName(),
            Context.class.getName(),
            Factory.class.getName(),
            MethodInterceptor.class.getName());

    private final ProcessingContext context;
    private final List<InjectPlugin> plugins;
    private final Diagnostics diagnostics;
    private final Set<AnnotationImplRequest> annotationImplRequests = new LinkedHashSet<>();

    ModelFactory(ProcessingContext context, List<InjectPlugin> plugins) {
        this.context = context;
        this.plugins = plugins;
        this.diagnostics = context.diagnostics();
    }

    /** {@return the annotation implementations requested since the last drain, then clears them} */
    Set<AnnotationImplRequest> drainAnnotationImplRequests() {
        Set<AnnotationImplRequest> drained = new LinkedHashSet<>(annotationImplRequests);
        annotationImplRequests.clear();
        return drained;
    }

    /** {@return the type utilities for the current processing environment} */
    Types types() {
        return context.types();
    }

    BeanModel buildModel(TypeElement bean) {
        Types types = context.types();
        Elements elements = context.elements();
        List<ParameterModel> constructorParameters = injectConstructor(bean)
                .map(constructor -> parameters(constructor, bean, types, elements, packageOf(bean)))
                .orElse(List.of());
        MemberInjection members = members(bean);
        Optional<ScopeDeclaration> scope = scope(bean);
        List<AnnotationMirror> inheritedScope = inheritedScope(bean, scope.orElse(null));
        LifecycleCallbacks callbacks = lifecycleCallbacks(bean, scope.isPresent(), false);
        ContributionModel contribution = contribution(bean, bean.asType(), types);
        RenderedQualifier beanQualifier = contribution != null
                ? null
                : Annotations.qualifier(bean, elements, packageOf(bean), annotationImplRequests)
                        .orElse(null);
        List<Element> constructorSites = new ArrayList<>();
        constructorSites.add(bean);
        injectConstructor(bean).ifPresent(constructorSites::add);
        List<TypeName> exposedTypes = contribution != null ? List.of() : exposedTypes(bean, beanQualifier != null);
        ClassName interceptsAnnotation = interceptsAnnotation(bean);
        InterceptionModel interception = interception(bean, interceptsAnnotation);
        return new BeanModel(
                packageOf(bean),
                ClassName.of(bean).generatedName("Factory").simpleName(),
                ClassName.of(bean),
                constructorParameters,
                Annotations.metadata(inheritedScope, constructorSites, elements),
                Annotations.metadata(inheritedScope, List.of(bean), elements),
                members.fields(),
                members.methods(),
                members.pluginFields(),
                scope.map(ScopeDeclaration::type).orElse(null),
                beanQualifier,
                exposedTypes,
                supertypeTypes(bean, exposedTypes, beanQualifier != null),
                contribution,
                callbacks.postConstruct(),
                callbacks.preDestroy(),
                interceptsAnnotation,
                interception,
                bean);
    }

    /**
     * {@return the assisted recipe executable's container (non-{@code @Assisted}) parameters, keyed by their
     * declaration index}
     *
     * <p>{@code @Assisted} parameters are skipped entirely: they are passed through verbatim by the generator and must
     * never go through dependency/type resolution, which would report spurious diagnostics for otherwise legal
     * caller-supplied parameter types (e.g. a framework type).
     */
    Map<Integer, ParameterModel> recipeParameters(AssistedRecipe recipe) {
        Types types = context.types();
        Elements elements = context.elements();
        TypeElement owner = recipe.owner();
        String targetPackage = packageOf(owner);
        List<? extends VariableElement> recipeParameters = recipe.executable().getParameters();
        Map<Integer, ParameterModel> result = new LinkedHashMap<>();
        for (int index = 0; index < recipeParameters.size(); index++) {
            VariableElement parameter = recipeParameters.get(index);
            if (MoreElements.isAnnotatedWith(parameter, Assisted.class)) {
                continue;
            }
            result.put(index, parameterModel(parameter, owner, types, elements, targetPackage));
        }
        return result;
    }

    /**
     * {@return {@code bean}'s {@code @Inject} field/method and plugin-field injection, independent of its constructor}
     */
    MemberInjection members(TypeElement bean) {
        Types types = context.types();
        Elements elements = context.elements();
        List<FieldModel> fields = new ArrayList<>();
        List<MethodModel> methods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (!MoreElements.isAnnotatedWith(enclosed, Inject.class)) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                fields.add(field(MoreElements.asVariable(enclosed), types, elements, packageOf(bean)));
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = MoreElements.asExecutable(enclosed);
                methods.add(new MethodModel(
                        method.getSimpleName().toString(),
                        parameters(method, bean, types, elements, packageOf(bean)),
                        Annotations.metadata(List.of(method), elements)));
            }
        }
        return new MemberInjection(List.copyOf(fields), List.copyOf(methods), pluginFields(bean));
    }

    BeanModel defaultModel(TypeElement type) {
        Elements elements = context.elements();
        RenderedQualifier beanQualifier = Annotations.qualifier(type, elements, packageOf(type), annotationImplRequests)
                .orElse(null);
        Optional<ScopeDeclaration> scope = scope(type);
        List<AnnotationMirror> inheritedScope = inheritedScope(type, scope.orElse(null));
        LifecycleCallbacks callbacks = lifecycleCallbacks(type, scope.isPresent(), false);
        ClassName interceptsAnnotation = interceptsAnnotation(type);
        List<TypeName> defaultExposed = exposedTypes(type, beanQualifier != null);
        return new BeanModel(
                packageOf(type),
                ClassName.of(type).generatedName("Factory").simpleName(),
                ClassName.of(type),
                List.of(),
                Annotations.metadata(inheritedScope, List.of(type), elements),
                Annotations.metadata(inheritedScope, List.of(type), elements),
                List.of(),
                List.of(),
                List.of(),
                scope.map(ScopeDeclaration::type).orElse(null),
                beanQualifier,
                defaultExposed,
                supertypeTypes(type, defaultExposed, beanQualifier != null),
                null,
                callbacks.postConstruct(),
                callbacks.preDestroy(),
                interceptsAnnotation,
                interception(type, interceptsAnnotation),
                type);
    }

    ProvidesModel providesModel(ExecutableElement method, TypeElement host) {
        Types types = context.types();
        Elements elements = context.elements();
        Optional<TypeElement> frameworkReturn =
                MoreTypes.asTypeElement(method.getReturnType()).filter(ModelFactory::isFrameworkType);
        if (frameworkReturn.isPresent()) {
            diagnostics
                    .on(method)
                    .error(frameworkReturn.get().getSimpleName() + " is a framework type and cannot be bound.");
        }
        String name = method.getSimpleName().toString();
        if (!interceptingAnnotations(method).isEmpty()) {
            diagnostics
                    .on(method)
                    .error("@Provides " + name
                            + "() cannot be intercepted: the container does not construct its result.");
        }
        ContributionModel contribution = contribution(method, method.getReturnType(), types);
        RenderedQualifier qualifier = contribution != null
                ? null
                : Annotations.qualifier(method, elements, packageOf(host), annotationImplRequests)
                        .orElse(null);
        List<TypeName> exposedTypes = contribution != null
                ? List.of()
                : exposedTypesForProvides(method, method.getReturnType(), qualifier != null);
        CodeBlock producedKeyType = qualifier == null && exposedTypes.isEmpty() && contribution == null
                ? collectionKeyExpression(method.getReturnType(), types)
                : null;
        return new ProvidesModel(
                packageOf(host),
                ClassName.generatedName(method, "Factory").simpleName(),
                TypeName.of(types.erasure(method.getReturnType())),
                ClassName.of(host),
                name,
                method.getModifiers().contains(Modifier.STATIC),
                parameters(method, host, types, elements, packageOf(host)),
                Annotations.metadata(List.of(host, method), elements),
                Annotations.metadata(List.of(method), elements),
                Annotations.scope(method).orElse(null),
                qualifier,
                exposedTypes,
                contribution,
                producedKeyType,
                MoreElements.isAnnotatedWith(method, Default.class),
                method);
    }

    boolean validateBean(TypeElement bean) {
        boolean valid = true;
        Optional<ExecutableElement> constructor = injectConstructor(bean);
        if (constructor.isPresent() && constructor.get().getModifiers().contains(Modifier.PRIVATE)) {
            diagnostics
                    .on(constructor.get())
                    .error("@Inject constructor of " + bean.getQualifiedName() + " must not be private.");
            valid = false;
        }
        if (constructor.isEmpty() && !hasAccessibleNoArgConstructor(bean)) {
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName()
                            + " has @Inject members but no @Inject constructor and no accessible no-argument"
                            + " constructor.");
            valid = false;
        }
        for (Element enclosed : bean.getEnclosedElements()) {
            if (!MoreElements.isAnnotatedWith(enclosed, Inject.class)) {
                continue;
            }
            boolean member = enclosed.getKind() == ElementKind.FIELD || enclosed.getKind() == ElementKind.METHOD;
            if (member
                    && (enclosed.getModifiers().contains(Modifier.PRIVATE)
                            || enclosed.getModifiers().contains(Modifier.STATIC))) {
                diagnostics
                        .on(enclosed)
                        .error("@Inject " + enclosed.getSimpleName() + " in " + bean.getQualifiedName()
                                + " must not be private or static.");
                valid = false;
            }
        }
        return valid;
    }

    List<TypeElement> dependencies(TypeElement bean, boolean includeProviderTargets) {
        Types types = context.types();
        List<TypeElement> result = new ArrayList<>();
        injectConstructor(bean)
                .ifPresent(constructor -> addParameterDependencies(constructor, types, result, includeProviderTargets));
        for (Element enclosed : bean.getEnclosedElements()) {
            if (!MoreElements.isAnnotatedWith(enclosed, Inject.class)) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                dependencyElement(enclosed.asType(), types, includeProviderTargets)
                        .ifPresent(result::add);
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                addParameterDependencies(MoreElements.asExecutable(enclosed), types, result, includeProviderTargets);
            }
        }
        return result;
    }

    void addParameterDependencies(ExecutableElement executable, List<TypeElement> result) {
        addParameterDependencies(executable, context.types(), result, true);
    }

    private static void addParameterDependencies(
            ExecutableElement executable, Types types, List<TypeElement> result, boolean includeProviderTargets) {
        for (VariableElement parameter : executable.getParameters()) {
            if (hasInjectingAnnotation(parameter)) {
                // Plugin-supplied argument, not a context dependency
                continue;
            }
            dependencyElement(parameter.asType(), types, includeProviderTargets).ifPresent(result::add);
        }
    }

    Optional<ExecutableElement> injectConstructor(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR && MoreElements.isAnnotatedWith(enclosed, Inject.class)) {
                return Optional.of(MoreElements.asExecutable(enclosed));
            }
        }
        return Optional.empty();
    }

    /**
     * {@return the bean type's scope: the scoping annotation declared on it, or else the one its supertypes declare}
     *
     * <p>Each supertype counts with its own scope, so the nearest declaration wins along each path, through the
     * superclass chain and all superinterfaces. Reports different scopes inherited along different paths.
     */
    Optional<ScopeDeclaration> scope(TypeElement bean) {
        Map<String, ScopeDeclaration> byType = new LinkedHashMap<>();
        for (ScopeDeclaration declaration : scopes(bean)) {
            byType.putIfAbsent(declaration.type().canonicalName(), declaration);
        }
        if (byType.size() > 1) {
            List<String> found = byType.values().stream()
                    .map(declaration -> "@" + declaration.annotationType().getSimpleName() + " from "
                            + declaration.declaringType().getSimpleName())
                    .toList();
            diagnostics
                    .on(bean)
                    .error(bean.getSimpleName() + " inherits " + (found.size() == 2 ? "two" : found.size())
                            + " scopes: " + String.join(", ", found.subList(0, found.size() - 1)) + " and "
                            + found.getLast() + ". Declare one on " + bean.getSimpleName() + ".");
            return Optional.empty();
        }
        return byType.values().stream().findFirst();
    }

    private static List<ScopeDeclaration> scopes(TypeElement type) {
        Optional<AnnotationMirror> declared = Annotations.scopeAnnotation(type);
        if (declared.isPresent()) {
            return List.of(new ScopeDeclaration(declared.get(), type));
        }
        List<ScopeDeclaration> inherited = new ArrayList<>();
        for (TypeMirror supertype : supertypes(type)) {
            MoreTypes.asTypeElement(supertype).ifPresent(declaring -> inherited.addAll(scopes(declaring)));
        }
        return inherited;
    }

    /**
     * {@return whether the bean type's scope depends on a supertype javac has not resolved yet} A type that declares
     * its own scope inherits none, so its supertypes do not matter.
     */
    static boolean hasUnresolvedScope(TypeElement type) {
        if (Annotations.scopeAnnotation(type).isPresent()) {
            return false;
        }
        for (TypeMirror supertype : supertypes(type)) {
            if (supertype.getKind() == TypeKind.ERROR
                    || MoreTypes.asTypeElement(supertype)
                            .map(ModelFactory::hasUnresolvedScope)
                            .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private static List<TypeMirror> supertypes(TypeElement type) {
        List<TypeMirror> supertypes = new ArrayList<>();
        if (type.getSuperclass().getKind() != TypeKind.NONE) {
            supertypes.add(type.getSuperclass());
        }
        supertypes.addAll(type.getInterfaces());
        return supertypes;
    }

    /** {@return the scoping annotation {@code bean} inherits, as a list for its metadata; empty if declared on it} */
    private static List<AnnotationMirror> inheritedScope(TypeElement bean, @Nullable ScopeDeclaration scope) {
        return scope == null || scope.declaringType().equals(bean) ? List.of() : List.of(scope.annotation());
    }

    /** {@return whether {@code executable} has at least one @Assisted parameter} */
    static boolean isAssisted(ExecutableElement executable) {
        return executable.getParameters().stream()
                .anyMatch(parameter -> MoreElements.isAnnotatedWith(parameter, Assisted.class));
    }

    /** Reports every annotation that contradicts a recipe having no binding of its own. */
    void validateAssistedRecipe(Element annotated) {
        if (hasQualifier(annotated)) {
            diagnostics.on(annotated).error("An assisted recipe cannot be qualified; it has no binding key.");
        }
        if (MoreElements.isAnnotatedWith(annotated, ExposeAs.class)) {
            diagnostics.on(annotated).error("An assisted recipe cannot use @ExposeAs; it has no binding key.");
        }
        if (MoreElements.isAnnotatedWith(annotated, IntoSet.class)
                || MoreElements.isAnnotatedWith(annotated, IntoMap.class)) {
            diagnostics
                    .on(annotated)
                    .error("An assisted recipe cannot contribute to a collection; it has no binding key.");
        }
        if (hasConditioningAnnotation(annotated)) {
            diagnostics
                    .on(annotated)
                    .error("An assisted recipe cannot be conditional; put the condition on the factory interface.");
        }
        if (!interceptingAnnotations(annotated).isEmpty()) {
            diagnostics
                    .on(annotated)
                    .error("An assisted recipe cannot be intercepted: assisted instances are built per call, outside"
                            + " the registry.");
        }
    }

    /**
     * {@return the bean's validated lifecycle callbacks} Reports callback-shape, count, and scope/assisted misuse.
     *
     * @param bean the bean type
     * @param scoped whether the bean has a scope
     * @param assisted whether the bean is assisted (caller-owned)
     */
    LifecycleCallbacks lifecycleCallbacks(TypeElement bean, boolean scoped, boolean assisted) {
        ExecutableElement postConstruct = singleCallback(bean, PostConstruct.class);
        ExecutableElement preDestroy = singleCallback(bean, PreDestroy.class);
        if (preDestroy != null && !scoped) {
            if (assisted) {
                diagnostics
                        .on(preDestroy)
                        .error("@PreDestroy is not allowed on an assisted bean; assisted instances are caller-owned.");
            } else {
                diagnostics
                        .on(preDestroy)
                        .error("@PreDestroy requires a scoped bean; an unscoped bean is never destroyed by the"
                                + " container.");
            }
        }
        return new LifecycleCallbacks(name(postConstruct), name(preDestroy));
    }

    /** {@return the bean's @PreDestroy method name without reporting diagnostics (generation-time reader)} */
    @Nullable
    String preDestroyName(TypeElement bean) {
        return name(firstCallback(bean, PreDestroy.class));
    }

    /**
     * {@return the scope of an assisted recipe's product, without reporting diagnostics (generation-time reader): the
     * {@code @Provides} method's own, or the one its class declares or inherits}
     */
    Optional<ClassName> recipeScope(AssistedRecipe recipe) {
        ExecutableElement providesMethod = recipe.providesMethod();
        if (providesMethod != null) {
            return Annotations.scope(providesMethod);
        }
        return scopes(recipe.owner()).stream().findFirst().map(ScopeDeclaration::type);
    }

    /** {@return the type expression of an assisted recipe's product key: its class, or the method's return type} */
    CodeBlock productType(AssistedRecipe recipe) {
        ExecutableElement providesMethod = recipe.providesMethod();
        if (providesMethod != null) {
            return typeExpression(providesMethod.getReturnType(), context.types(), providesMethod);
        }
        return CodeBlock.of("$T.class", ClassName.of(recipe.owner()));
    }

    /** {@return the bean's @PostConstruct method name without reporting diagnostics (generation-time reader)} */
    @Nullable
    String postConstructName(TypeElement bean) {
        return name(firstCallback(bean, PostConstruct.class));
    }

    private @Nullable ExecutableElement singleCallback(TypeElement bean, Class<? extends Annotation> annotation) {
        ExecutableElement found = null;
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !MoreElements.isAnnotatedWith(enclosed, annotation)) {
                continue;
            }
            ExecutableElement method = MoreElements.asExecutable(enclosed);
            if (found != null) {
                diagnostics
                        .on(method)
                        .error("A class may declare at most one @" + annotation.getSimpleName() + " method.");
                continue;
            }
            if (!isValidCallback(method)) {
                diagnostics
                        .on(method)
                        .error("A @" + annotation.getSimpleName()
                                + " callback must be a non-private, non-static, no-argument void method.");
            }
            found = method;
        }
        return found;
    }

    private static @Nullable ExecutableElement firstCallback(TypeElement bean, Class<? extends Annotation> annotation) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && MoreElements.isAnnotatedWith(enclosed, annotation)) {
                return MoreElements.asExecutable(enclosed);
            }
        }
        return null;
    }

    private static boolean isValidCallback(ExecutableElement method) {
        return method.getReturnType().getKind() == TypeKind.VOID
                && method.getParameters().isEmpty()
                && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }

    private static @Nullable String name(@Nullable ExecutableElement method) {
        return method == null ? null : method.getSimpleName().toString();
    }

    /** {@return whether the element carries an annotation meta-annotated {@link ConditioningAnnotation}} */
    private static boolean hasConditioningAnnotation(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    MoreElements.asType(mirror.getAnnotationType().asElement());
            if (MoreElements.isAnnotatedWith(annotationType, ConditioningAnnotation.class)) {
                return true;
            }
        }
        return false;
    }

    /** Reports @Assisted parameters on the bean's @Inject methods; they belong only on recipes. */
    void validateNoMisplacedAssisted(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && MoreElements.isAnnotatedWith(enclosed, Inject.class)) {
                for (VariableElement parameter :
                        MoreElements.asExecutable(enclosed).getParameters()) {
                    if (MoreElements.isAnnotatedWith(parameter, Assisted.class)) {
                        diagnostics
                                .on(parameter)
                                .error("@Assisted is misplaced: only @Inject constructors and @Provides methods take"
                                        + " assisted parameters.");
                    }
                }
            }
        }
    }

    /** {@return the container-resolved (non-assisted) declared parameter types of {@code executable}} */
    List<TypeElement> containerDependencies(ExecutableElement executable) {
        List<TypeElement> dependencies = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            if (MoreElements.isAnnotatedWith(parameter, Assisted.class)) {
                continue;
            }
            TypeMirror type = parameter.asType();
            Optional<TypeMirror> inner = providerInner(type);
            TypeMirror resolved = inner.orElse(type);
            MoreTypes.asTypeElement(resolved).ifPresent(dependencies::add);
        }
        return List.copyOf(dependencies);
    }

    boolean hasAccessibleNoArgConstructor(TypeElement type) {
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        if (constructors.isEmpty()) {
            // Implicit default constructor
            return true;
        }
        return constructors.stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && !constructor.getModifiers().contains(Modifier.PRIVATE));
    }

    /** {@return the annotation named by {@code @Intercepts} on {@code bean}, or {@code null} when it carries none} */
    @Nullable
    ClassName interceptsAnnotation(TypeElement bean) {
        Optional<AnnotationReader> intercepts = context.annotation(bean, Intercepts.class.getCanonicalName());
        if (intercepts.isEmpty()) {
            return null;
        }
        if (!context.isSubtype(bean.asType(), MethodInterceptor.class)) {
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName()
                            + " is annotated @Intercepts but does not implement MethodInterceptor.");
            return null;
        }
        return MoreTypes.asTypeElement(intercepts.get().type("value"))
                .map(ClassName::of)
                .orElse(null);
    }

    /**
     * {@return whether {@code bean} has an interceptable shape, given that it (or one of its methods) carries an
     * intercepting annotation; reports the shape violation, if any, via {@link #validateInterceptableShape}}
     *
     * <p>Returns {@code true} without reporting anything when {@code bean} carries no intercepting annotation at all:
     * such a bean is not subject to the interception shape rules in the first place. Called ahead of
     * {@link #validateBean} so a bean that both fails the interception shape check and would otherwise trip a
     * plain-bean diagnostic (e.g. an intercepted enum, which has no accessible no-argument constructor by construction)
     * gets exactly the interception-specific error.
     *
     * @param bean the candidate bean type
     */
    boolean validateInterceptionShape(TypeElement bean) {
        if (!carriesInterceptingAnnotation(bean)) {
            return true;
        }
        return validateInterceptableShape(bean);
    }

    /** {@return whether {@code bean} or one of its methods carries an intercepting annotation} */
    private static boolean carriesInterceptingAnnotation(TypeElement bean) {
        if (!interceptingAnnotations(bean).isEmpty()) {
            return true;
        }
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD
                    && !interceptingAnnotations(enclosed).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return {@code bean}'s interception model, or {@code null} when nothing is intercepted}
     *
     * <p>Reports every placement a generated subclass cannot honour. A class-level annotation sweeps declared methods
     * that are not {@code private}, {@code static}, or {@code final}, skipping {@code Object} methods and framework
     * callbacks; a method-level annotation applies to that method alone and replaces a class-level declaration of the
     * same annotation type in place, so the class-level position (outermost) is preserved. When every intercepting
     * annotation found on the bean was consumed by a silently-skipped {@code @Provides} method, this returns
     * {@code null} without reporting: {@link #providesModel} is the single reporter for that case.
     *
     * @param bean the bean type
     * @param interceptsAnnotation the result of {@link #interceptsAnnotation(TypeElement)} for {@code bean}, passed in
     *     rather than recomputed so a bean that is both {@code @Intercepts}-annotated and shape-invalid is reported
     *     only once
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength", "checkstyle:ReturnCount"})
    @Nullable
    InterceptionModel interception(TypeElement bean, @Nullable ClassName interceptsAnnotation) {
        Elements elements = context.elements();
        List<AnnotationMirror> classLevel = interceptingAnnotations(bean);
        List<ExecutableElement> annotatedMethods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD
                    && !interceptingAnnotations(enclosed).isEmpty()) {
                annotatedMethods.add(MoreElements.asExecutable(enclosed));
            }
        }
        if (classLevel.isEmpty() && annotatedMethods.isEmpty()) {
            return null;
        }
        if (interceptsAnnotation != null) {
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName() + " is an interceptor and cannot itself be intercepted.");
            return null;
        }
        if (!validateInterceptableShape(bean)) {
            return null;
        }
        List<InterceptedMethodModel> methods = new ArrayList<>();
        int providesSkipped = 0;
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = MoreElements.asExecutable(enclosed);
            List<AnnotationMirror> own = interceptingAnnotations(method);
            boolean eligible = isInterceptable(method);
            if (!own.isEmpty() && !eligible) {
                if (MoreElements.isAnnotatedWith(method, Provides.class)) {
                    providesSkipped++;
                    // Skip @Provides methods silently; diagnostic reported in providesModel
                    continue;
                }
                diagnostics.on(method).error(ineligibleReason(method));
                return null;
            }
            if (!eligible || (own.isEmpty() && classLevel.isEmpty())) {
                continue;
            }
            Map<String, AnnotationMirror> merged = new LinkedHashMap<>();
            for (AnnotationMirror mirror : classLevel) {
                merged.put(mirror.getAnnotationType().toString(), mirror);
            }
            for (AnnotationMirror mirror : own) {
                merged.put(mirror.getAnnotationType().toString(), mirror);
            }
            methods.add(interceptedMethod(method, methods.size(), List.copyOf(merged.values()), elements));
        }
        if (methods.isEmpty()) {
            if (classLevel.isEmpty() && providesSkipped > 0 && providesSkipped == annotatedMethods.size()) {
                // Every intercepting annotation on the bean was consumed by a silently-skipped @Provides method;
                // providesModel is the single reporter for that case.
                return null;
            }
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName() + " carries an intercepting annotation but declares no interceptable"
                            + " method.");
            return null;
        }
        return new InterceptionModel(
                ClassName.of(bean).generatedName("Intercepted").simpleName(),
                injectConstructor(bean).map(ModelFactory::proxyParameters).orElse(List.of()),
                List.copyOf(methods));
    }

    /** {@return the intercepting annotations declared on {@code element}, in source order} */
    private static List<AnnotationMirror> interceptingAnnotations(Element element) {
        List<AnnotationMirror> result = new ArrayList<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    MoreElements.asType(mirror.getAnnotationType().asElement());
            if (MoreElements.isAnnotatedWith(annotationType, InterceptingAnnotation.class)) {
                result.add(mirror);
            }
        }
        return result;
    }

    /** {@return {@code true} if {@code bean} can be subclassed by a generated proxy; reports why not otherwise} */
    @SuppressWarnings("checkstyle:ReturnCount")
    private boolean validateInterceptableShape(TypeElement bean) {
        if (bean.getKind() == ElementKind.RECORD) {
            diagnostics
                    .on(bean)
                    .error("A record cannot be intercepted: records are implicitly final and their accessors are part"
                            + " of their identity. Use a class.");
            return false;
        }
        if (bean.getKind() == ElementKind.ENUM) {
            diagnostics
                    .on(bean)
                    .error("An enum cannot be intercepted: enum constants are created by the JVM, not by a factory.");
            return false;
        }
        if (bean.getModifiers().contains(Modifier.SEALED)) {
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName() + " cannot be intercepted: a sealed class permits only its listed"
                            + " subclasses. Use a non-sealed class.");
            return false;
        }
        if (bean.getModifiers().contains(Modifier.FINAL)) {
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName()
                            + " cannot be intercepted: the generated proxy must subclass it. Remove" + " final.");
            return false;
        }
        if (!bean.getTypeParameters().isEmpty()) {
            diagnostics
                    .on(bean)
                    .error(bean.getQualifiedName()
                            + " cannot be intercepted: a generic class is bound under its raw type.");
            return false;
        }
        return true;
    }

    /** {@return whether a class-level sweep may override {@code method}} */
    private static boolean isInterceptable(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        return !modifiers.contains(Modifier.PRIVATE)
                && !modifiers.contains(Modifier.STATIC)
                && !modifiers.contains(Modifier.FINAL)
                && !hasTypeVariableThrows(method)
                && !MoreElements.isAnnotatedWith(method, Inject.class)
                && !MoreElements.isAnnotatedWith(method, PostConstruct.class)
                && !MoreElements.isAnnotatedWith(method, PreDestroy.class)
                && !MoreElements.isAnnotatedWith(method, Provides.class)
                && !isObjectMethod(method);
    }

    /** {@return why a method with its own intercepting annotation cannot be intercepted} */
    @SuppressWarnings("checkstyle:ReturnCount")
    private static String ineligibleReason(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        String name = method.getSimpleName().toString();
        if (modifiers.contains(Modifier.PRIVATE)) {
            return name + "() cannot be intercepted: it is private.";
        }
        if (modifiers.contains(Modifier.STATIC)) {
            return name + "() cannot be intercepted: it is static.";
        }
        if (modifiers.contains(Modifier.FINAL)) {
            return name + "() cannot be intercepted: it is final.";
        }
        if (hasTypeVariableThrows(method)) {
            return name + "() cannot be intercepted: a type variable in its throws clause cannot be caught.";
        }
        return name + "() cannot be intercepted: framework callbacks are invoked by the container.";
    }

    /**
     * {@return whether {@code method}'s throws clause contains a type variable, which Java forbids as a catch
     * parameter}
     */
    private static boolean hasTypeVariableThrows(ExecutableElement method) {
        for (TypeMirror thrown : method.getThrownTypes()) {
            if (thrown.getKind() == TypeKind.TYPEVAR) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether {@code method} overrides one of {@code Object}'s methods} */
    private static boolean isObjectMethod(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.isEmpty()) {
            return "toString".equals(name) || "hashCode".equals(name);
        }
        return "equals".equals(name)
                && parameters.size() == 1
                && MoreTypes.isType(parameters.get(0).asType(), Object.class);
    }

    /** {@return {@code executable}'s parameters as proxy signature parameters} */
    private static List<ProxyParameter> proxyParameters(ExecutableElement executable) {
        List<ProxyParameter> result = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            result.add(new ProxyParameter(parameter.getSimpleName().toString(), TypeName.of(parameter.asType())));
        }
        return List.copyOf(result);
    }

    /** {@return the model for one intercepted method} */
    private InterceptedMethodModel interceptedMethod(
            ExecutableElement method, int index, List<AnnotationMirror> bindings, Elements elements) {
        List<CodeBlock> rendered = new ArrayList<>();
        for (AnnotationMirror mirror : bindings) {
            rendered.add(Annotations.annotationValue(
                    MoreElements.asType(mirror.getAnnotationType().asElement()), mirror, elements));
        }
        TypeName returnType = TypeName.of(method.getReturnType());
        List<TypeVariableName> typeVariables =
                method.getTypeParameters().stream().map(TypeVariableName::of).toList();
        List<ProxyParameter> parameters = proxyParameters(method);
        boolean parametersUncheckedCast = method.getParameters().stream()
                .anyMatch(parameter -> MoreTypes.isCastFromObjectUnchecked(parameter.asType()));
        return new InterceptedMethodModel(
                method.getSimpleName().toString(),
                method.getSimpleName() + "$" + index,
                visibility(method),
                returnType,
                parameters,
                thrownTypes(method),
                declaresThrowable(method),
                method.isVarArgs(),
                MoreTypes.isCastFromObjectUnchecked(method.getReturnType()),
                parametersUncheckedCast,
                typeVariables,
                List.copyOf(rendered));
    }

    /**
     * {@return {@code method}'s visibility modifier, or {@code null} when it is package-private}
     *
     * <p>The generated override mirrors it: widening a {@code protected} or package-private method to {@code public}
     * would expose it on the proxy. {@code private} methods never reach here; they are rejected as uninterceptable.
     */
    private static @Nullable Modifier visibility(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC)) {
            return Modifier.PUBLIC;
        }
        if (modifiers.contains(Modifier.PROTECTED)) {
            return Modifier.PROTECTED;
        }
        return null;
    }

    /** {@return whether {@code method} declares {@code Throwable} itself} */
    private boolean declaresThrowable(ExecutableElement method) {
        Types types = context.types();
        Elements elements = context.elements();
        TypeMirror throwable =
                elements.getTypeElement(Throwable.class.getCanonicalName()).asType();
        for (TypeMirror thrown : method.getThrownTypes()) {
            if (types.isSameType(thrown, throwable)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return {@code method}'s declared checked exception types eligible for their own catch clause, with unchecked
     * types and exact {@code Throwable} removed, keeping only the most general type of each hierarchy branch}
     *
     * <p>Unchecked types are already covered by the proxy's {@code catch (RuntimeException | Error)}, and exact
     * {@code Throwable} is handled separately via {@link #declaresThrowable}. Rather than sorting candidates by
     * specificity &mdash; not a well-defined total order across unrelated exception types, and therefore unsafe to sort
     * by &mdash; this keeps a declared type only when no other declared type is its strict supertype. The result is a
     * set of mutually unrelated types in stable declaration order, so no ordering of the emitted {@code catch} clauses
     * can be unreachable.
     */
    private List<TypeName> thrownTypes(ExecutableElement method) {
        Types types = context.types();
        Elements elements = context.elements();
        TypeMirror runtimeException = elements.getTypeElement(RuntimeException.class.getCanonicalName())
                .asType();
        TypeMirror error =
                elements.getTypeElement(Error.class.getCanonicalName()).asType();
        TypeMirror throwable =
                elements.getTypeElement(Throwable.class.getCanonicalName()).asType();
        List<TypeMirror> candidates = new ArrayList<>(method.getThrownTypes());
        List<TypeMirror> kept = new ArrayList<>();
        for (TypeMirror thrown : candidates) {
            if (types.isSubtype(thrown, runtimeException)
                    || types.isSubtype(thrown, error)
                    || types.isSameType(thrown, throwable)) {
                continue;
            }
            boolean subsumedByAnother = candidates.stream()
                    .anyMatch(other -> !types.isSameType(other, thrown) && types.isSubtype(thrown, other));
            if (!subsumedByAnother) {
                kept.add(thrown);
            }
        }
        return kept.stream().map(TypeName::of).toList();
    }

    private List<TypeName> exposedTypes(TypeElement bean, boolean qualified) {
        Types types = context.types();
        List<? extends TypeMirror> declared = exposeAsMirrors(bean);
        if (declared != null) {
            if (declared.isEmpty()) {
                diagnostics.on(bean).error(bean.getQualifiedName() + " @ExposeAs must specify at least one type.");
                return List.of();
            }
            List<TypeName> result = new ArrayList<>();
            for (TypeMirror exposed : declared) {
                Optional<TypeElement> frameworkType =
                        MoreTypes.asTypeElement(exposed).filter(ModelFactory::isFrameworkType);
                if (frameworkType.isPresent()) {
                    diagnostics
                            .on(bean)
                            .error(frameworkType.get().getSimpleName() + " is a framework type and cannot be bound.");
                } else if (types.isAssignable(bean.asType(), exposed)) {
                    result.add(TypeName.of(types.erasure(exposed)));
                } else {
                    diagnostics
                            .on(bean)
                            .error("Cannot expose " + bean.getQualifiedName() + " as unrelated type " + exposed
                                    + "; it is not a supertype of the bean.");
                }
            }
            return result;
        }
        List<TypeElement> hierarchy = supertypeClosure(bean, types);
        if (hierarchy.size() == 1 && !qualified) {
            // Just the bean type, unqualified: the factory's default key already covers it
            return List.of();
        }
        return hierarchy.stream().map(type -> (TypeName) ClassName.of(type)).toList();
    }

    /**
     * {@return the types among {@code exposedTypes} that {@code bean} has only through its supertypes: all but its own
     * class when they come from its supertype closure, none when {@code @ExposeAs} chose them or a qualifier makes
     * every key explicit}
     */
    private List<TypeName> supertypeTypes(TypeElement bean, List<TypeName> exposedTypes, boolean qualified) {
        if (qualified || exposeAsMirrors(bean) != null) {
            return List.of();
        }
        ClassName own = ClassName.of(bean);
        return exposedTypes.stream().filter(type -> !type.equals(own)).toList();
    }

    private List<TypeName> exposedTypesForProvides(
            ExecutableElement method, TypeMirror producedType, boolean qualified) {
        Types types = context.types();
        List<? extends TypeMirror> declared = exposeAsMirrors(method);
        if (declared != null) {
            if (declared.isEmpty()) {
                diagnostics
                        .on(method)
                        .error("@ExposeAs on " + method.getSimpleName() + " must specify at least one type.");
                return List.of();
            }
            List<TypeName> result = new ArrayList<>();
            for (TypeMirror exposed : declared) {
                Optional<TypeElement> frameworkType =
                        MoreTypes.asTypeElement(exposed).filter(ModelFactory::isFrameworkType);
                if (frameworkType.isPresent()) {
                    diagnostics
                            .on(method)
                            .error(frameworkType.get().getSimpleName() + " is a framework type and cannot be bound.");
                } else if (types.isAssignable(producedType, exposed)) {
                    result.add(TypeName.of(types.erasure(exposed)));
                } else {
                    diagnostics
                            .on(method)
                            .error("Cannot expose the result of " + method.getSimpleName() + " as unrelated type "
                                    + exposed + "; it is not a supertype of the produced type.");
                }
            }
            return result;
        }
        if (qualified) {
            return List.of(TypeName.of(types.erasure(producedType)));
        }
        return List.of();
    }

    /** {@return the element's collection contribution, or {@code null}; reports marker misuse} */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:ReturnCount"})
    private @Nullable ContributionModel contribution(Element element, TypeMirror contributedType, Types types) {
        IntoSet intoSet = element.getAnnotation(IntoSet.class);
        IntoMap intoMap = element.getAnnotation(IntoMap.class);
        if (intoSet == null && intoMap == null) {
            return null;
        }
        if (intoSet != null && intoMap != null) {
            diagnostics.on(element).error("Use only one collection marker (@IntoSet or @IntoMap).");
            return null;
        }
        if (MoreElements.isAnnotatedWith(element, ExposeAs.class)) {
            diagnostics.on(element).error("A collection contribution cannot also use @ExposeAs.");
            return null;
        }
        if (hasQualifier(element)) {
            diagnostics.on(element).error("Collection bindings are unqualified; remove the qualifier.");
            return null;
        }
        TypeMirror elementType = context.annotation(
                        element, (intoSet != null ? IntoSet.class : IntoMap.class).getCanonicalName())
                .orElseThrow()
                .type("value");
        Optional<TypeElement> frameworkType =
                MoreTypes.asTypeElement(elementType).filter(ModelFactory::isFrameworkType);
        if (frameworkType.isPresent()) {
            diagnostics
                    .on(element)
                    .error(frameworkType.get().getSimpleName() + " is a framework type and cannot be bound.");
            return null;
        }
        if (!types.isAssignable(types.erasure(contributedType), types.erasure(elementType))) {
            diagnostics
                    .on(element)
                    .error("The bean is not assignable to the collection element type " + types.erasure(elementType)
                            + ".");
            return null;
        }
        String mapKey = intoMap != null ? intoMap.key() : null;
        if (mapKey != null && mapKey.isBlank()) {
            diagnostics.on(element).error("@IntoMap key must not be blank.");
            return null;
        }
        return new ContributionModel(TypeName.of(types.erasure(elementType)), mapKey);
    }

    /** {@return whether {@code element} is one of the framework types that can never be bound or injected} */
    private static boolean isFrameworkType(TypeElement element) {
        String qualified = element.getQualifiedName().toString();
        return qualified.equals(INJECTOR) || qualified.equals(CONTEXT) || qualified.equals(FACTORY);
    }

    /** {@return whether the element carries a qualifying annotation, without rendering it or registering an impl} */
    private static boolean hasQualifier(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    MoreElements.asType(mirror.getAnnotationType().asElement());
            if (MoreElements.isAnnotatedWith(annotationType, QualifyingAnnotation.class)) {
                return true;
            }
        }
        return false;
    }

    private List<PluginField> pluginFields(TypeElement bean) {
        List<PluginField> result = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD || !hasInjectingAnnotation(enclosed)) {
                continue;
            }
            VariableElement field = MoreElements.asVariable(enclosed);
            if (field.getModifiers().contains(Modifier.PRIVATE)
                    || field.getModifiers().contains(Modifier.STATIC)
                    || field.getModifiers().contains(Modifier.FINAL)) {
                diagnostics
                        .on(field)
                        .error("Field " + field.getSimpleName() + " in " + bean.getQualifiedName()
                                + " annotated with an injecting annotation must not be private, static, or final.");
                continue;
            }
            Optional<CodeBlock> initializer = pluginInitializer(field, bean);
            if (initializer.isEmpty()) {
                diagnostics
                        .on(field)
                        .error("No plugin handles the injecting annotation on field " + field.getSimpleName() + " in "
                                + bean.getQualifiedName() + ".");
                continue;
            }
            result.add(new PluginField(field.getSimpleName().toString(), initializer.get()));
        }
        return List.copyOf(result);
    }

    private Optional<CodeBlock> pluginInitializer(VariableElement field, TypeElement bean) {
        InjectionSite site = new InjectionSite(field, bean, field.asType(), context.environment());
        for (InjectPlugin plugin : plugins) {
            Optional<CodeBlock> initializer = plugin.initializer(site);
            if (initializer.isPresent()) {
                return initializer;
            }
        }
        return Optional.empty();
    }

    private List<ParameterModel> parameters(
            ExecutableElement executable,
            TypeElement declaringType,
            Types types,
            Elements elements,
            String targetPackage) {
        return executable.getParameters().stream()
                .map(parameter -> parameterModel(parameter, declaringType, types, elements, targetPackage))
                .toList();
    }

    private ParameterModel parameterModel(
            VariableElement parameter,
            TypeElement declaringType,
            Types types,
            Elements elements,
            String targetPackage) {
        CodeBlock pluginArgument = null;
        if (hasInjectingAnnotation(parameter)) {
            Optional<CodeBlock> supplied = pluginInitializer(parameter, declaringType);
            if (supplied.isEmpty()) {
                diagnostics
                        .on(parameter)
                        .error("No plugin handles the injecting annotation on parameter " + parameter.getSimpleName()
                                + " of " + declaringType.getQualifiedName() + ".");
            } else {
                pluginArgument = supplied.get();
            }
        }
        Dependency dependency = dependency(parameter.asType(), types, parameter);
        RenderedQualifier qualifier = Annotations.qualifier(parameter, elements, targetPackage, annotationImplRequests)
                .orElse(null);
        return new ParameterModel(
                parameter.getSimpleName().toString(),
                dependency.type(),
                dependency.typeArgument(),
                dependency.erasure(),
                qualifier,
                dependency.isProvider(),
                pluginArgument,
                Annotations.metadata(List.of(parameter), elements));
    }

    private static String packageOf(TypeElement type) {
        return MoreElements.packageOf(type).getQualifiedName().toString();
    }

    private @Nullable List<TypeMirror> exposeAsMirrors(Element element) {
        return context.annotation(element, ExposeAs.class.getCanonicalName())
                .map(exposeAs -> exposeAs.types("value"))
                .orElse(null);
    }

    private static List<TypeElement> supertypeClosure(TypeElement bean, Types types) {
        LinkedHashSet<TypeElement> collected = new LinkedHashSet<>();
        Deque<TypeMirror> queue = new ArrayDeque<>();
        queue.add(bean.asType());
        while (!queue.isEmpty()) {
            TypeMirror current = queue.poll();
            Optional<TypeElement> typeElement = MoreTypes.asTypeElement(current);
            if (typeElement.isEmpty()
                    || EXCLUDED_DEFAULT_EXPOSURES.contains(
                            typeElement.get().getQualifiedName().toString())) {
                continue;
            }
            if (collected.add(typeElement.get())) {
                queue.addAll(types.directSupertypes(current));
            }
        }
        return List.copyOf(collected);
    }

    private static boolean hasInjectingAnnotation(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (MoreElements.isAnnotatedWith(mirror.getAnnotationType().asElement(), InjectingAnnotation.class)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<TypeElement> dependencyElement(
            TypeMirror type, Types types, boolean includeProviderTargets) {
        Optional<TypeMirror> inner = providerInner(type);
        if (inner.isPresent() && !includeProviderTargets) {
            return Optional.empty();
        }
        return MoreTypes.asTypeElement(types.erasure(inner.orElse(type)));
    }

    private FieldModel field(VariableElement fieldElement, Types types, Elements elements, String targetPackage) {
        Dependency dependency = dependency(fieldElement.asType(), types, fieldElement);
        RenderedQualifier qualifier = Annotations.qualifier(
                        fieldElement, elements, targetPackage, annotationImplRequests)
                .orElse(null);
        return new FieldModel(
                fieldElement.getSimpleName().toString(),
                dependency.type(),
                dependency.typeArgument(),
                dependency.erasure(),
                qualifier,
                dependency.isProvider(),
                Annotations.metadata(List.of(fieldElement), elements));
    }

    private Dependency dependency(TypeMirror type, Types types, Element site) {
        Optional<TypeMirror> providerInner = providerInner(type);
        TypeMirror dependency = providerInner.orElse(type);
        TypeMirror erasure = types.erasure(dependency);
        return new Dependency(
                typeExpression(dependency, types, site),
                providerInner.isPresent(),
                typeArgument(dependency, erasure),
                TypeName.of(erasure));
    }

    /**
     * {@return the type to name as the explicit type argument of a resolution call: {@code dependency} itself, boxed;
     * its erasure when it is a wildcard or mentions a type variable, neither of which the factory can name}
     */
    private static TypeName typeArgument(TypeMirror dependency, TypeMirror erasure) {
        boolean nameable = dependency.getKind() != TypeKind.WILDCARD && !mentionsTypeVariable(dependency);
        return TypeName.of(nameable ? dependency : erasure).box();
    }

    /** {@return whether {@code type} is, or has among its type arguments or components, a type variable} */
    private static boolean mentionsTypeVariable(TypeMirror type) {
        return switch (type.getKind()) {
            case TYPEVAR -> true;
            case DECLARED ->
                MoreTypes.asDeclared(type).getTypeArguments().stream().anyMatch(ModelFactory::mentionsTypeVariable);
            case ARRAY -> mentionsTypeVariable(MoreTypes.asArray(type).getComponentType());
            case WILDCARD -> {
                WildcardType wildcard = MoreTypes.asWildcard(type);
                TypeMirror extendsBound = wildcard.getExtendsBound();
                TypeMirror superBound = wildcard.getSuperBound();
                yield (extendsBound != null && mentionsTypeVariable(extendsBound))
                        || (superBound != null && mentionsTypeVariable(superBound));
            }
            default -> false;
        };
    }

    /** {@return the runtime type expression for a dependency: a class literal, or a Types factory call} */
    private CodeBlock typeExpression(TypeMirror type, Types types, Element site) {
        if (type.getKind() == TypeKind.DECLARED) {
            DeclaredType declared = MoreTypes.asDeclared(type);
            String name =
                    MoreElements.asType(declared.asElement()).getQualifiedName().toString();
            if (name.equals(CONTEXT)) {
                diagnostics.on(site).error("Context cannot be injected; inject Injector instead.");
            } else if (name.equals(FACTORY)) {
                diagnostics.on(site).error("Factory cannot be injected.");
            }
            if (name.equals(SET)) {
                if (declared.getTypeArguments().size() != 1) {
                    diagnostics.on(site).error("Cannot inject a raw Set; declare an element type.");
                } else if (providerInner(declared.getTypeArguments().get(0)).isPresent()) {
                    diagnostics.on(site).error("Set<Provider<T>> is not supported; inject Provider<Set<T>> instead.");
                } else {
                    return CodeBlock.of(
                            "$T.setOf($T.class)",
                            TYPES,
                            TypeName.of(
                                    types.erasure(declared.getTypeArguments().get(0))));
                }
            }
            if (name.equals(MAP)) {
                if (declared.getTypeArguments().size() != 2) {
                    diagnostics.on(site).error("Cannot inject a raw Map; declare key and value types.");
                } else if (!MoreTypes.isType(declared.getTypeArguments().get(0), String.class)) {
                    diagnostics.on(site).error("Injected Maps use String keys.");
                } else {
                    return CodeBlock.of(
                            "$T.mapOf($T.class, $T.class)",
                            TYPES,
                            TypeName.of(
                                    types.erasure(declared.getTypeArguments().get(0))),
                            TypeName.of(
                                    types.erasure(declared.getTypeArguments().get(1))));
                }
            }
        }
        return CodeBlock.of("$T.class", TypeName.of(types.erasure(type)));
    }

    /**
     * {@return the parameterized collection key type expression for a {@code Set<E>}/{@code Map<String, V>} type, or
     * {@code null} when {@code type} isn't exactly one of those (raw, wrong arity, non-String map key, or a
     * {@code Provider} element); emits no diagnostics, unlike {@link #typeExpression}}
     */
    @SuppressWarnings("checkstyle:ReturnCount")
    private @Nullable CodeBlock collectionKeyExpression(TypeMirror type, Types types) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        DeclaredType declared = MoreTypes.asDeclared(type);
        String name =
                MoreElements.asType(declared.asElement()).getQualifiedName().toString();
        if (name.equals(SET) && declared.getTypeArguments().size() == 1) {
            TypeMirror elementType = declared.getTypeArguments().get(0);
            if (providerInner(elementType).isPresent()) {
                return null;
            }
            return CodeBlock.of("$T.setOf($T.class)", TYPES, TypeName.of(types.erasure(elementType)));
        }
        if (name.equals(MAP)
                && declared.getTypeArguments().size() == 2
                && MoreTypes.isType(declared.getTypeArguments().get(0), String.class)) {
            return CodeBlock.of(
                    "$T.mapOf($T.class, $T.class)",
                    TYPES,
                    TypeName.of(types.erasure(declared.getTypeArguments().get(0))),
                    TypeName.of(types.erasure(declared.getTypeArguments().get(1))));
        }
        return null;
    }

    private static Optional<TypeMirror> providerInner(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return Optional.empty();
        }
        DeclaredType declared = MoreTypes.asDeclared(type);
        if (declared.getTypeArguments().size() == 1
                && MoreElements.asType(declared.asElement()).getQualifiedName().contentEquals(PROVIDER)) {
            return Optional.of(declared.getTypeArguments().get(0));
        }
        return Optional.empty();
    }

    /**
     * A dependency as the generated code needs it.
     *
     * @param type the runtime type expression for its injection point
     * @param isProvider whether it is injected as a {@code Provider} of the type
     * @param typeArgument the explicit type argument of the resolution call
     * @param erasure the erased type, passed to the resolution call as a class literal
     */
    private record Dependency(CodeBlock type, boolean isProvider, TypeName typeArgument, TypeName erasure) {}
}
