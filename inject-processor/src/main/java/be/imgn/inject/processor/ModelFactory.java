package be.imgn.inject.processor;

import java.io.Closeable;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EventListener;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Provider;
import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;

/**
 * Translates {@code javax.lang.model} elements into the generator's models ({@link BeanModel}, {@link ProvidesModel})
 * and answers structural questions about them (dependencies, accessibility, exposed types). Shared by the gather and
 * plan stages.
 */
final class ModelFactory {

    private static final String PROVIDER = Provider.class.getCanonicalName();

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
            EventListener.class.getName());

    private final ProcessingEnvironment environment;
    private final List<InjectPlugin> plugins;
    private final Diagnostics diagnostics;

    ModelFactory(ProcessingEnvironment environment, List<InjectPlugin> plugins, Diagnostics diagnostics) {
        this.environment = environment;
        this.plugins = plugins;
        this.diagnostics = diagnostics;
    }

    BeanModel buildModel(TypeElement bean) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        List<ParameterModel> constructorParameters = injectConstructor(bean)
                .map(constructor -> parameters(constructor, bean, types, elements))
                .orElse(List.of());
        List<FieldModel> fields = new ArrayList<>();
        List<MethodModel> methods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getAnnotation(Inject.class) == null) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                fields.add(field((VariableElement) enclosed, types, elements));
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                methods.add(
                        new MethodModel(method.getSimpleName().toString(), parameters(method, bean, types, elements)));
            }
        }
        CodeBlock beanQualifier = Annotations.qualifier(bean, elements).orElse(null);
        return new BeanModel(
                packageOf(bean),
                GeneratedNames.factoryName(enclosingChain(bean)),
                ClassName.get(bean),
                constructorParameters,
                List.copyOf(fields),
                List.copyOf(methods),
                pluginFields(bean),
                Annotations.scope(bean).orElse(null),
                beanQualifier,
                exposedTypes(bean, beanQualifier != null));
    }

    BeanModel defaultModel(TypeElement type) {
        CodeBlock beanQualifier =
                Annotations.qualifier(type, environment.getElementUtils()).orElse(null);
        return new BeanModel(
                packageOf(type),
                GeneratedNames.factoryName(enclosingChain(type)),
                ClassName.get(type),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Annotations.scope(type).orElse(null),
                beanQualifier,
                exposedTypes(type, beanQualifier != null));
    }

    ProvidesModel providesModel(ExecutableElement method, TypeElement host, int index) {
        Types types = environment.getTypeUtils();
        Elements elements = environment.getElementUtils();
        String name = method.getSimpleName().toString();
        CodeBlock qualifier = Annotations.qualifier(method, elements).orElse(null);
        return new ProvidesModel(
                packageOf(host),
                GeneratedNames.providesFactoryName(enclosingChain(host), name, index),
                TypeName.get(types.erasure(method.getReturnType())),
                ClassName.get(host),
                name,
                method.getModifiers().contains(Modifier.STATIC),
                parameters(method, host, types, elements),
                Annotations.scope(method).orElse(null),
                qualifier,
                exposedTypesForProvides(method, method.getReturnType(), qualifier != null));
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
                if (types.isAssignable(bean.asType(), exposed)) {
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
                if (types.isAssignable(producedType, exposed)) {
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
            ExecutableElement executable, TypeElement declaringType, Types types, Elements elements) {
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
                    Dependency dependency = dependency(parameter.asType(), types);
                    CodeBlock qualifier =
                            Annotations.qualifier(parameter, elements).orElse(null);
                    return new ParameterModel(
                            parameter.getSimpleName().toString(),
                            dependency.rawType(),
                            qualifier,
                            dependency.isProvider(),
                            pluginArgument);
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

    private static FieldModel field(VariableElement fieldElement, Types types, Elements elements) {
        Dependency dependency = dependency(fieldElement.asType(), types);
        CodeBlock qualifier = Annotations.qualifier(fieldElement, elements).orElse(null);
        return new FieldModel(
                fieldElement.getSimpleName().toString(), dependency.rawType(), qualifier, dependency.isProvider());
    }

    private static Dependency dependency(TypeMirror type, Types types) {
        Optional<TypeMirror> inner = providerInner(type);
        if (inner.isPresent()) {
            return new Dependency(TypeName.get(types.erasure(inner.get())), true);
        }
        return new Dependency(TypeName.get(types.erasure(type)), false);
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

    private static List<String> enclosingChain(TypeElement bean) {
        Deque<String> chain = new ArrayDeque<>();
        Element current = bean;
        while (current instanceof TypeElement typeElement) {
            chain.addFirst(typeElement.getSimpleName().toString());
            current = typeElement.getEnclosingElement();
        }
        return List.copyOf(chain);
    }

    private record Dependency(TypeName rawType, boolean isProvider) {}
}
