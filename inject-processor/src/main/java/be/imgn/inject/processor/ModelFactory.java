package be.imgn.inject.processor;

import java.io.Closeable;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EventListener;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Assisted;
import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Injector;
import be.imgn.inject.IntoMap;
import be.imgn.inject.IntoSet;
import be.imgn.inject.Provider;
import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;

/**
 * Translates {@code javax.lang.model} elements into the generator's models ({@link BeanModel}, {@link ProvidesModel})
 * and answers structural questions about them (dependencies, accessibility, exposed types). Shared by the gather and
 * plan stages.
 */
final class ModelFactory {

    private static final String PROVIDER = Provider.class.getCanonicalName();
    private static final String SET = Set.class.getName();
    private static final String MAP = Map.class.getName();
    private static final String STRING = String.class.getName();
    private static final String INJECTOR = Injector.class.getCanonicalName();
    private static final String CONTEXT = Context.class.getCanonicalName();
    private static final String FACTORY = Factory.class.getCanonicalName();
    private static final ClassName TYPES = ClassName.get("be.imgn.inject", "Types");

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
            Factory.class.getName());

    private final ProcessingEnvironment environment;
    private final List<InjectPlugin> plugins;
    private final Diagnostics diagnostics;
    private final Set<AnnotationImplRequest> annotationImplRequests = new LinkedHashSet<>();

    ModelFactory(ProcessingEnvironment environment, List<InjectPlugin> plugins, Diagnostics diagnostics) {
        this.environment = environment;
        this.plugins = plugins;
        this.diagnostics = diagnostics;
    }

    /** {@return the annotation implementations requested since the last drain, then clears them} */
    Set<AnnotationImplRequest> drainAnnotationImplRequests() {
        Set<AnnotationImplRequest> drained = new LinkedHashSet<>(annotationImplRequests);
        annotationImplRequests.clear();
        return drained;
    }

    /** {@return the type utilities for the current processing environment} */
    Types types() {
        return environment.getTypeUtils();
    }

    BeanModel buildModel(TypeElement bean) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        List<ParameterModel> constructorParameters = injectConstructor(bean)
                .map(constructor -> parameters(constructor, bean, types, elements, packageOf(bean)))
                .orElse(List.of());
        MemberInjection members = members(bean);
        ContributionModel contribution = contribution(bean, bean.asType(), types);
        RenderedQualifier beanQualifier = contribution != null
                ? null
                : Annotations.qualifier(bean, elements, packageOf(bean), annotationImplRequests)
                        .orElse(null);
        List<Element> constructorSites = new ArrayList<>();
        constructorSites.add(bean);
        injectConstructor(bean).ifPresent(constructorSites::add);
        List<TypeName> exposedTypes = contribution != null ? List.of() : exposedTypes(bean, beanQualifier != null);
        return new BeanModel(
                packageOf(bean),
                GeneratedNames.factoryName(enclosingChain(bean)),
                ClassName.get(bean),
                constructorParameters,
                Annotations.metadata(constructorSites, elements),
                Annotations.metadata(List.of(bean), elements),
                members.fields(),
                members.methods(),
                members.pluginFields(),
                Annotations.scope(bean).orElse(null),
                beanQualifier,
                exposedTypes,
                contribution);
    }

    /** {@return the assisted recipe executable's parameters (assisted and container alike), in declaration order} */
    List<ParameterModel> recipeParameters(AssistedRecipe recipe) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        return parameters(recipe.executable(), recipe.owner(), types, elements, packageOf(recipe.owner()));
    }

    /**
     * {@return {@code bean}'s {@code @Inject} field/method and plugin-field injection, independent of its constructor}
     */
    MemberInjection members(TypeElement bean) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        List<FieldModel> fields = new ArrayList<>();
        List<MethodModel> methods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getAnnotation(Inject.class) == null) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                fields.add(field((VariableElement) enclosed, types, elements, packageOf(bean)));
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                methods.add(new MethodModel(
                        method.getSimpleName().toString(),
                        parameters(method, bean, types, elements, packageOf(bean)),
                        Annotations.metadata(List.of(method), elements)));
            }
        }
        return new MemberInjection(List.copyOf(fields), List.copyOf(methods), pluginFields(bean));
    }

    BeanModel defaultModel(TypeElement type) {
        Elements elements = environment.getElementUtils();
        RenderedQualifier beanQualifier = Annotations.qualifier(type, elements, packageOf(type), annotationImplRequests)
                .orElse(null);
        return new BeanModel(
                packageOf(type),
                GeneratedNames.factoryName(enclosingChain(type)),
                ClassName.get(type),
                List.of(),
                Annotations.metadata(List.of(type), elements),
                Annotations.metadata(List.of(type), elements),
                List.of(),
                List.of(),
                List.of(),
                Annotations.scope(type).orElse(null),
                beanQualifier,
                exposedTypes(type, beanQualifier != null),
                null);
    }

    ProvidesModel providesModel(ExecutableElement method, TypeElement host, int index) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        if (method.getReturnType() instanceof DeclaredType declaredReturn
                && declaredReturn.asElement() instanceof TypeElement returnElement
                && isFrameworkType(returnElement)) {
            diagnostics.error(returnElement.getSimpleName() + " is a framework type and cannot be bound.", method);
        }
        String name = method.getSimpleName().toString();
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
                GeneratedNames.providesFactoryName(enclosingChain(host), name, index),
                TypeName.get(types.erasure(method.getReturnType())),
                ClassName.get(host),
                name,
                method.getModifiers().contains(Modifier.STATIC),
                parameters(method, host, types, elements, packageOf(host)),
                Annotations.metadata(List.of(host, method), elements),
                Annotations.metadata(List.of(method), elements),
                Annotations.scope(method).orElse(null),
                qualifier,
                exposedTypes,
                contribution,
                producedKeyType);
    }

    boolean validateBean(TypeElement bean) {
        boolean valid = true;
        Optional<ExecutableElement> constructor = injectConstructor(bean);
        if (constructor.isPresent() && constructor.get().getModifiers().contains(Modifier.PRIVATE)) {
            diagnostics.error(
                    "@Inject constructor of " + bean.getQualifiedName() + " must not be private.", constructor.get());
            valid = false;
        }
        if (constructor.isEmpty() && !hasAccessibleNoArgConstructor(bean)) {
            diagnostics.error(
                    bean.getQualifiedName()
                            + " has @Inject members but no @Inject constructor and no accessible no-argument"
                            + " constructor.",
                    bean);
            valid = false;
        }
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getAnnotation(Inject.class) == null) {
                continue;
            }
            boolean member = enclosed.getKind() == ElementKind.FIELD || enclosed.getKind() == ElementKind.METHOD;
            if (member
                    && (enclosed.getModifiers().contains(Modifier.PRIVATE)
                            || enclosed.getModifiers().contains(Modifier.STATIC))) {
                diagnostics.error(
                        "@Inject " + enclosed.getSimpleName() + " in " + bean.getQualifiedName()
                                + " must not be private or static.",
                        enclosed);
                valid = false;
            }
        }
        return valid;
    }

    List<TypeElement> dependencies(TypeElement bean, boolean includeProviderTargets) {
        Types types = environment.getTypeUtils();
        List<TypeElement> result = new ArrayList<>();
        injectConstructor(bean)
                .ifPresent(constructor -> addParameterDependencies(constructor, types, result, includeProviderTargets));
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getAnnotation(Inject.class) == null) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                dependencyElement(enclosed.asType(), types, includeProviderTargets)
                        .ifPresent(result::add);
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                addParameterDependencies((ExecutableElement) enclosed, types, result, includeProviderTargets);
            }
        }
        return result;
    }

    void addParameterDependencies(ExecutableElement executable, List<TypeElement> result) {
        addParameterDependencies(executable, environment.getTypeUtils(), result, true);
    }

    Optional<ExecutableElement> injectConstructor(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR && enclosed.getAnnotation(Inject.class) != null) {
                return Optional.of((ExecutableElement) enclosed);
            }
        }
        return Optional.empty();
    }

    /** {@return whether {@code executable} has at least one @Assisted parameter} */
    static boolean isAssisted(ExecutableElement executable) {
        return executable.getParameters().stream()
                .anyMatch(parameter -> parameter.getAnnotation(Assisted.class) != null);
    }

    /** Reports every annotation that contradicts a recipe having no binding of its own. */
    void validateAssistedRecipe(Element annotated) {
        if (Annotations.scope(annotated).isPresent()) {
            diagnostics.error(
                    "An assisted recipe cannot be scoped; every factory call builds a fresh instance.", annotated);
        }
        if (hasQualifier(annotated)) {
            diagnostics.error("An assisted recipe cannot be qualified; it has no binding key.", annotated);
        }
        if (annotated.getAnnotation(ExposeAs.class) != null) {
            diagnostics.error("An assisted recipe cannot use @ExposeAs; it has no binding key.", annotated);
        }
        if (annotated.getAnnotation(IntoSet.class) != null || annotated.getAnnotation(IntoMap.class) != null) {
            diagnostics.error(
                    "An assisted recipe cannot contribute to a collection; it has no binding key.", annotated);
        }
    }

    /** Reports @Assisted parameters on the bean's @Inject methods; they belong only on recipes. */
    void validateNoMisplacedAssisted(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && enclosed.getAnnotation(Inject.class) != null) {
                for (VariableElement parameter : ((ExecutableElement) enclosed).getParameters()) {
                    if (parameter.getAnnotation(Assisted.class) != null) {
                        diagnostics.error(
                                "@Assisted is misplaced: only @Inject constructors and @Provides methods take"
                                        + " assisted parameters.",
                                parameter);
                    }
                }
            }
        }
    }

    /** {@return the container-resolved (non-assisted) declared parameter types of {@code executable}} */
    List<TypeElement> containerDependencies(ExecutableElement executable) {
        List<TypeElement> dependencies = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            if (parameter.getAnnotation(Assisted.class) != null) {
                continue;
            }
            TypeMirror type = parameter.asType();
            Optional<TypeMirror> inner = providerInner(type);
            TypeMirror resolved = inner.orElse(type);
            if (resolved instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
                dependencies.add(element);
            }
        }
        return List.copyOf(dependencies);
    }

    boolean hasAccessibleNoArgConstructor(TypeElement type) {
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        if (constructors.isEmpty()) {
            return true; // implicit default constructor
        }
        return constructors.stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && !constructor.getModifiers().contains(Modifier.PRIVATE));
    }

    private List<TypeName> exposedTypes(TypeElement bean, boolean qualified) {
        Types types = environment.getTypeUtils();
        List<? extends TypeMirror> declared = exposeAsMirrors(bean);
        if (declared != null) {
            if (declared.isEmpty()) {
                diagnostics.error(bean.getQualifiedName() + " @ExposeAs must specify at least one type.", bean);
                return List.of();
            }
            List<TypeName> result = new ArrayList<>();
            for (TypeMirror exposed : declared) {
                if (exposed instanceof DeclaredType declaredExposed
                        && declaredExposed.asElement() instanceof TypeElement exposedElement
                        && isFrameworkType(exposedElement)) {
                    diagnostics.error(
                            exposedElement.getSimpleName() + " is a framework type and cannot be bound.", bean);
                } else if (types.isAssignable(bean.asType(), exposed)) {
                    result.add(TypeName.get(types.erasure(exposed)));
                } else {
                    diagnostics.error(
                            "Cannot expose " + bean.getQualifiedName() + " as unrelated type " + exposed
                                    + "; it is not a supertype of the bean.",
                            bean);
                }
            }
            return result;
        }
        List<TypeElement> hierarchy = supertypeClosure(bean, types);
        if (hierarchy.size() == 1 && !qualified) {
            return List.of(); // just the bean type, unqualified: the factory's default key already covers it
        }
        return hierarchy.stream().map(type -> (TypeName) ClassName.get(type)).toList();
    }

    private List<TypeName> exposedTypesForProvides(
            ExecutableElement method, TypeMirror producedType, boolean qualified) {
        Types types = environment.getTypeUtils();
        List<? extends TypeMirror> declared = exposeAsMirrors(method);
        if (declared != null) {
            if (declared.isEmpty()) {
                diagnostics.error(
                        "@ExposeAs on " + method.getSimpleName() + " must specify at least one type.", method);
                return List.of();
            }
            List<TypeName> result = new ArrayList<>();
            for (TypeMirror exposed : declared) {
                if (exposed instanceof DeclaredType declaredExposed
                        && declaredExposed.asElement() instanceof TypeElement exposedElement
                        && isFrameworkType(exposedElement)) {
                    diagnostics.error(
                            exposedElement.getSimpleName() + " is a framework type and cannot be bound.", method);
                } else if (types.isAssignable(producedType, exposed)) {
                    result.add(TypeName.get(types.erasure(exposed)));
                } else {
                    diagnostics.error(
                            "Cannot expose the result of " + method.getSimpleName() + " as unrelated type " + exposed
                                    + "; it is not a supertype of the produced type.",
                            method);
                }
            }
            return result;
        }
        if (qualified) {
            return List.of(TypeName.get(types.erasure(producedType)));
        }
        return List.of();
    }

    /** {@return the element's collection contribution, or {@code null}; reports marker misuse} */
    private @Nullable ContributionModel contribution(Element element, TypeMirror contributedType, Types types) {
        IntoSet intoSet = element.getAnnotation(IntoSet.class);
        IntoMap intoMap = element.getAnnotation(IntoMap.class);
        if (intoSet == null && intoMap == null) {
            return null;
        }
        if (intoSet != null && intoMap != null) {
            diagnostics.error("Use only one collection marker (@IntoSet or @IntoMap).", element);
            return null;
        }
        if (element.getAnnotation(ExposeAs.class) != null) {
            diagnostics.error("A collection contribution cannot also use @ExposeAs.", element);
            return null;
        }
        if (hasQualifier(element)) {
            diagnostics.error("Collection bindings are unqualified; remove the qualifier.", element);
            return null;
        }
        TypeMirror elementType =
                intoSet != null ? annotationClassValue(intoSet::value) : annotationClassValue(intoMap::value);
        if (elementType instanceof DeclaredType declaredElementType
                && declaredElementType.asElement() instanceof TypeElement elementTypeElement
                && isFrameworkType(elementTypeElement)) {
            diagnostics.error(
                    elementTypeElement.getSimpleName() + " is a framework type and cannot be bound.", element);
            return null;
        }
        if (!types.isAssignable(types.erasure(contributedType), types.erasure(elementType))) {
            diagnostics.error(
                    "The bean is not assignable to the collection element type " + types.erasure(elementType) + ".",
                    element);
            return null;
        }
        String mapKey = intoMap != null ? intoMap.key() : null;
        if (mapKey != null && mapKey.isBlank()) {
            diagnostics.error("@IntoMap key must not be blank.", element);
            return null;
        }
        return new ContributionModel(TypeName.get(types.erasure(elementType)), mapKey);
    }

    /** {@return the TypeMirror of a Class-valued annotation member (the standard MirroredTypeException dance)} */
    private static TypeMirror annotationClassValue(Supplier<Class<?>> accessor) {
        try {
            var unused = accessor.get();
            throw new IllegalStateException("annotation Class member did not throw");
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
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
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getAnnotation(QualifyingAnnotation.class) != null) {
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
            VariableElement field = (VariableElement) enclosed;
            if (field.getModifiers().contains(Modifier.PRIVATE)
                    || field.getModifiers().contains(Modifier.STATIC)
                    || field.getModifiers().contains(Modifier.FINAL)) {
                diagnostics.error(
                        "Field " + field.getSimpleName() + " in " + bean.getQualifiedName()
                                + " annotated with an injecting annotation must not be private, static, or final.",
                        field);
                continue;
            }
            Optional<CodeBlock> initializer = pluginInitializer(field, bean);
            if (initializer.isEmpty()) {
                diagnostics.error(
                        "No plugin handles the injecting annotation on field " + field.getSimpleName() + " in "
                                + bean.getQualifiedName() + ".",
                        field);
                continue;
            }
            result.add(new PluginField(field.getSimpleName().toString(), initializer.get()));
        }
        return List.copyOf(result);
    }

    private Optional<CodeBlock> pluginInitializer(VariableElement field, TypeElement bean) {
        InjectionSite site = new InjectionSite(field, bean, field.asType(), environment);
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
                .map(parameter -> {
                    CodeBlock pluginArgument = null;
                    if (hasInjectingAnnotation(parameter)) {
                        Optional<CodeBlock> supplied = pluginInitializer(parameter, declaringType);
                        if (supplied.isEmpty()) {
                            diagnostics.error(
                                    "No plugin handles the injecting annotation on parameter "
                                            + parameter.getSimpleName() + " of " + declaringType.getQualifiedName()
                                            + ".",
                                    parameter);
                        } else {
                            pluginArgument = supplied.get();
                        }
                    }
                    Dependency dependency = dependency(parameter.asType(), types, parameter);
                    RenderedQualifier qualifier = Annotations.qualifier(
                                    parameter, elements, targetPackage, annotationImplRequests)
                            .orElse(null);
                    return new ParameterModel(
                            parameter.getSimpleName().toString(),
                            dependency.type(),
                            qualifier,
                            dependency.isProvider(),
                            pluginArgument,
                            Annotations.metadata(List.of(parameter), elements));
                })
                .toList();
    }

    private String packageOf(TypeElement type) {
        return environment
                .getElementUtils()
                .getPackageOf(type)
                .getQualifiedName()
                .toString();
    }

    private static @Nullable List<? extends TypeMirror> exposeAsMirrors(Element element) {
        ExposeAs exposeAs = element.getAnnotation(ExposeAs.class);
        if (exposeAs == null) {
            return null;
        }
        try {
            exposeAs.value();
            return List.of();
        } catch (MirroredTypesException mirrors) {
            return mirrors.getTypeMirrors();
        }
    }

    private static List<TypeElement> supertypeClosure(TypeElement bean, Types types) {
        LinkedHashSet<TypeElement> collected = new LinkedHashSet<>();
        Deque<TypeMirror> queue = new ArrayDeque<>();
        queue.add(bean.asType());
        while (!queue.isEmpty()) {
            TypeMirror current = queue.poll();
            if (current instanceof DeclaredType declared && declared.asElement() instanceof TypeElement typeElement) {
                if (EXCLUDED_DEFAULT_EXPOSURES.contains(
                        typeElement.getQualifiedName().toString())) {
                    continue;
                }
                if (collected.add(typeElement)) {
                    queue.addAll(types.directSupertypes(current));
                }
            }
        }
        return List.copyOf(collected);
    }

    private static boolean hasInjectingAnnotation(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getAnnotation(InjectingAnnotation.class)
                    != null) {
                return true;
            }
        }
        return false;
    }

    private static void addParameterDependencies(
            ExecutableElement executable, Types types, List<TypeElement> result, boolean includeProviderTargets) {
        for (VariableElement parameter : executable.getParameters()) {
            if (hasInjectingAnnotation(parameter)) {
                continue; // plugin-supplied argument, not a context dependency
            }
            dependencyElement(parameter.asType(), types, includeProviderTargets).ifPresent(result::add);
        }
    }

    private static Optional<TypeElement> dependencyElement(
            TypeMirror type, Types types, boolean includeProviderTargets) {
        Optional<TypeMirror> inner = providerInner(type);
        if (inner.isPresent() && !includeProviderTargets) {
            return Optional.empty();
        }
        TypeMirror effective = inner.orElse(type);
        if (types.erasure(effective) instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement typeElement) {
            return Optional.of(typeElement);
        }
        return Optional.empty();
    }

    private FieldModel field(VariableElement fieldElement, Types types, Elements elements, String targetPackage) {
        Dependency dependency = dependency(fieldElement.asType(), types, fieldElement);
        RenderedQualifier qualifier = Annotations.qualifier(
                        fieldElement, elements, targetPackage, annotationImplRequests)
                .orElse(null);
        return new FieldModel(
                fieldElement.getSimpleName().toString(),
                dependency.type(),
                qualifier,
                dependency.isProvider(),
                Annotations.metadata(List.of(fieldElement), elements));
    }

    private Dependency dependency(TypeMirror type, Types types, Element site) {
        Optional<TypeMirror> providerInner = providerInner(type);
        if (providerInner.isPresent()) {
            return new Dependency(typeExpression(providerInner.get(), types, site), true);
        }
        return new Dependency(typeExpression(type, types, site), false);
    }

    /** {@return the runtime type expression for a dependency: a class literal, or a Types factory call} */
    private CodeBlock typeExpression(TypeMirror type, Types types, Element site) {
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            String name = element.getQualifiedName().toString();
            if (name.equals(CONTEXT)) {
                diagnostics.error("Context cannot be injected; inject Injector instead.", site);
            } else if (name.equals(FACTORY)) {
                diagnostics.error("Factory cannot be injected.", site);
            }
            if (name.equals(SET)) {
                if (declared.getTypeArguments().size() != 1) {
                    diagnostics.error("Cannot inject a raw Set; declare an element type.", site);
                } else if (providerInner(declared.getTypeArguments().get(0)).isPresent()) {
                    diagnostics.error("Set<Provider<T>> is not supported; inject Provider<Set<T>> instead.", site);
                } else {
                    return CodeBlock.of(
                            "$T.setOf($T.class)",
                            TYPES,
                            TypeName.get(
                                    types.erasure(declared.getTypeArguments().get(0))));
                }
            }
            if (name.equals(MAP)) {
                if (declared.getTypeArguments().size() != 2) {
                    diagnostics.error("Cannot inject a raw Map; declare key and value types.", site);
                } else if (!isStringType(declared.getTypeArguments().get(0))) {
                    diagnostics.error("Injected Maps use String keys.", site);
                } else {
                    return CodeBlock.of(
                            "$T.mapOf($T.class, $T.class)",
                            TYPES,
                            TypeName.get(
                                    types.erasure(declared.getTypeArguments().get(0))),
                            TypeName.get(
                                    types.erasure(declared.getTypeArguments().get(1))));
                }
            }
        }
        return CodeBlock.of("$T.class", TypeName.get(types.erasure(type)));
    }

    /**
     * {@return the parameterized collection key type expression for a {@code Set<E>}/{@code Map<String, V>} type, or
     * {@code null} when {@code type} isn't exactly one of those (raw, wrong arity, non-String map key, or a
     * {@code Provider} element); emits no diagnostics, unlike {@link #typeExpression}}
     */
    private @Nullable CodeBlock collectionKeyExpression(TypeMirror type, Types types) {
        if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
            return null;
        }
        String name = element.getQualifiedName().toString();
        if (name.equals(SET) && declared.getTypeArguments().size() == 1) {
            TypeMirror elementType = declared.getTypeArguments().get(0);
            if (providerInner(elementType).isPresent()) {
                return null;
            }
            return CodeBlock.of("$T.setOf($T.class)", TYPES, TypeName.get(types.erasure(elementType)));
        }
        if (name.equals(MAP)
                && declared.getTypeArguments().size() == 2
                && isStringType(declared.getTypeArguments().get(0))) {
            return CodeBlock.of(
                    "$T.mapOf($T.class, $T.class)",
                    TYPES,
                    TypeName.get(types.erasure(declared.getTypeArguments().get(0))),
                    TypeName.get(types.erasure(declared.getTypeArguments().get(1))));
        }
        return null;
    }

    /** {@return whether {@code type} is exactly {@link String}} */
    private static boolean isStringType(TypeMirror type) {
        return type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element
                && element.getQualifiedName().contentEquals(STRING);
    }

    private static Optional<TypeMirror> providerInner(TypeMirror type) {
        if (type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement typeElement
                && typeElement.getQualifiedName().contentEquals(PROVIDER)
                && declared.getTypeArguments().size() == 1) {
            return Optional.of(declared.getTypeArguments().get(0));
        }
        return Optional.empty();
    }

    static List<String> enclosingChain(TypeElement bean) {
        Deque<String> chain = new ArrayDeque<>();
        Element current = bean;
        while (current instanceof TypeElement typeElement) {
            chain.addFirst(typeElement.getSimpleName().toString());
            current = typeElement.getEnclosingElement();
        }
        return List.copyOf(chain);
    }

    private record Dependency(CodeBlock type, boolean isProvider) {}
}
