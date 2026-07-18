package be.imgn.inject.processor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Inject;
import be.imgn.inject.Provider;
import be.imgn.inject.Provides;

/**
 * Annotation processor that generates one zero-reflection {@code Factory} subclass per injectable class. Handles
 * {@code @Inject} constructors, fields, and methods, qualifying and scoping annotations, {@code Provider<T>} injection,
 * default factories for undeclared in-compilation dependencies, and compile-time cycle/missing-dependency validation.
 * {@code @Provides} and service registration are added in later tasks.
 */
@SupportedAnnotationTypes("*")
public final class InjectProcessor extends AbstractProcessor {

    private static final String PROVIDER = Provider.class.getCanonicalName();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_21;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        Types types = processingEnv.getTypeUtils();

        Set<TypeElement> beanTypes = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Inject.class)) {
            if (element.getEnclosingElement() instanceof TypeElement bean) {
                beanTypes.add(bean);
            }
        }

        Map<TypeElement, BeanModel> toGenerate = new LinkedHashMap<>();
        Map<TypeElement, List<TypeElement>> edges = new LinkedHashMap<>();
        for (TypeElement bean : beanTypes) {
            toGenerate.put(bean, buildModel(bean));
            edges.put(bean, dependencyTypes(bean, types));
        }

        List<ProvidesModel> providesModels = gatherProvides(roundEnv);
        planDefaultFactories(toGenerate, edges, roundEnv, providesDependencies(roundEnv, types));

        List<TypeElement> cycle = findCycle(toGenerate.keySet(), edges);
        if (cycle != null) {
            String path = cycle.stream()
                    .map(type -> type.getQualifiedName().toString())
                    .collect(Collectors.joining(" -> "));
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Dependency cycle detected: " + path, cycle.get(0));
            return false;
        }

        for (BeanModel model : toGenerate.values()) {
            write(model);
        }
        for (ProvidesModel model : providesModels) {
            writeProvides(model);
        }
        return false;
    }

    private List<ProvidesModel> gatherProvides(RoundEnvironment roundEnv) {
        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();
        Map<String, Integer> indices = new HashMap<>();
        List<ProvidesModel> models = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Provides.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            if (!(method.getEnclosingElement() instanceof TypeElement host)) {
                continue;
            }
            String key = host.getQualifiedName().toString() + "#" + method.getSimpleName();
            int index = indices.merge(key, 0, (existing, ignored) -> existing + 1);
            models.add(providesModel(method, host, index, types, elements));
        }
        return models;
    }

    private ProvidesModel providesModel(
            ExecutableElement method, TypeElement host, int index, Types types, Elements elements) {
        String name = method.getSimpleName().toString();
        return new ProvidesModel(
                packageOf(host),
                GeneratedNames.providesFactoryName(enclosingChain(host), name, index),
                TypeName.get(types.erasure(method.getReturnType())),
                ClassName.get(host),
                name,
                method.getModifiers().contains(Modifier.STATIC),
                parameters(method, types, elements),
                Annotations.scope(method).orElse(null),
                Annotations.qualifier(method, elements).orElse(null));
    }

    private List<TypeElement> providesDependencies(RoundEnvironment roundEnv, Types types) {
        List<TypeElement> dependencies = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Provides.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            if (!method.getModifiers().contains(Modifier.STATIC)
                    && method.getEnclosingElement() instanceof TypeElement host) {
                dependencies.add(host);
            }
            addParameterDependencies(method, types, dependencies);
        }
        return dependencies;
    }

    private void writeProvides(ProvidesModel model) {
        try {
            ProvidesFactoryGenerator.generate(model).writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write @Provides factory: " + e.getMessage());
        }
    }

    private void planDefaultFactories(
            Map<TypeElement, BeanModel> toGenerate,
            Map<TypeElement, List<TypeElement>> edges,
            RoundEnvironment roundEnv,
            Collection<TypeElement> extraRoots) {
        Deque<TypeElement> queue = new ArrayDeque<>();
        edges.values().forEach(queue::addAll);
        queue.addAll(extraRoots);
        Set<TypeElement> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeElement dependency = queue.poll();
            if (!seen.add(dependency) || toGenerate.containsKey(dependency)) {
                continue;
            }
            if (!roundEnv.getRootElements().contains(dependency)) {
                continue; // library / cross-module type: satisfied elsewhere, checked at runtime
            }
            if (injectConstructor(dependency).isPresent()) {
                continue; // already an @Inject bean handled above
            }
            if (hasAccessibleNoArgConstructor(dependency)) {
                toGenerate.put(dependency, defaultModel(dependency));
                edges.put(dependency, List.of());
            } else {
                processingEnv
                        .getMessager()
                        .printMessage(
                                Diagnostic.Kind.ERROR,
                                "Cannot satisfy dependency " + dependency.getQualifiedName()
                                        + ": it has no @Inject constructor and no accessible no-argument constructor."
                                        + " Annotate it with @Inject or provide it with @Provides.",
                                dependency);
            }
        }
    }

    private void write(BeanModel model) {
        try {
            BeanFactoryGenerator.generate(model).writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write factory: " + e.getMessage());
        }
    }

    private BeanModel buildModel(TypeElement bean) {
        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();
        List<ParameterModel> constructorParameters = injectConstructor(bean)
                .map(constructor -> parameters(constructor, types, elements))
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
                methods.add(new MethodModel(method.getSimpleName().toString(), parameters(method, types, elements)));
            }
        }
        return new BeanModel(
                packageOf(bean),
                GeneratedNames.factoryName(enclosingChain(bean)),
                ClassName.get(bean),
                constructorParameters,
                List.copyOf(fields),
                List.copyOf(methods),
                Annotations.scope(bean).orElse(null),
                Annotations.qualifier(bean, elements).orElse(null));
    }

    private BeanModel defaultModel(TypeElement type) {
        return new BeanModel(
                packageOf(type),
                GeneratedNames.factoryName(enclosingChain(type)),
                ClassName.get(type),
                List.of(),
                List.of(),
                List.of(),
                Annotations.scope(type).orElse(null),
                Annotations.qualifier(type, processingEnv.getElementUtils()).orElse(null));
    }

    private String packageOf(TypeElement type) {
        return processingEnv
                .getElementUtils()
                .getPackageOf(type)
                .getQualifiedName()
                .toString();
    }

    private List<TypeElement> dependencyTypes(TypeElement bean, Types types) {
        List<TypeElement> result = new ArrayList<>();
        injectConstructor(bean).ifPresent(constructor -> addParameterDependencies(constructor, types, result));
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getAnnotation(Inject.class) == null) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                dependencyElement(enclosed.asType(), types).ifPresent(result::add);
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                addParameterDependencies((ExecutableElement) enclosed, types, result);
            }
        }
        return result;
    }

    private static void addParameterDependencies(ExecutableElement executable, Types types, List<TypeElement> result) {
        for (VariableElement parameter : executable.getParameters()) {
            dependencyElement(parameter.asType(), types).ifPresent(result::add);
        }
    }

    private static Optional<TypeElement> dependencyElement(TypeMirror type, Types types) {
        TypeMirror effective = providerInner(type).orElse(type);
        if (types.erasure(effective) instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement typeElement) {
            return Optional.of(typeElement);
        }
        return Optional.empty();
    }

    private static Optional<ExecutableElement> injectConstructor(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR && enclosed.getAnnotation(Inject.class) != null) {
                return Optional.of((ExecutableElement) enclosed);
            }
        }
        return Optional.empty();
    }

    private static List<ParameterModel> parameters(ExecutableElement executable, Types types, Elements elements) {
        return executable.getParameters().stream()
                .map(parameter -> {
                    Dependency dependency = dependency(parameter.asType(), types);
                    CodeBlock qualifier =
                            Annotations.qualifier(parameter, elements).orElse(null);
                    return new ParameterModel(
                            parameter.getSimpleName().toString(),
                            dependency.rawType(),
                            qualifier,
                            dependency.isProvider());
                })
                .toList();
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

    private static boolean hasAccessibleNoArgConstructor(TypeElement type) {
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

    private static @Nullable List<TypeElement> findCycle(
            Set<TypeElement> nodes, Map<TypeElement, List<TypeElement>> edges) {
        Set<TypeElement> visited = new HashSet<>();
        Deque<TypeElement> stack = new ArrayDeque<>();
        Set<TypeElement> onStack = new HashSet<>();
        for (TypeElement node : nodes) {
            List<TypeElement> cycle = visit(node, nodes, edges, visited, stack, onStack);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static @Nullable List<TypeElement> visit(
            TypeElement node,
            Set<TypeElement> nodes,
            Map<TypeElement, List<TypeElement>> edges,
            Set<TypeElement> visited,
            Deque<TypeElement> stack,
            Set<TypeElement> onStack) {
        if (onStack.contains(node)) {
            List<TypeElement> path = new ArrayList<>();
            boolean started = false;
            for (TypeElement element : stack) {
                started = started || element.equals(node);
                if (started) {
                    path.add(element);
                }
            }
            path.add(node);
            return path;
        }
        if (!visited.add(node)) {
            return null;
        }
        stack.addLast(node);
        onStack.add(node);
        for (TypeElement dependency : edges.getOrDefault(node, List.of())) {
            if (!nodes.contains(dependency)) {
                continue;
            }
            List<TypeElement> cycle = visit(dependency, nodes, edges, visited, stack, onStack);
            if (cycle != null) {
                return cycle;
            }
        }
        stack.removeLast();
        onStack.remove(node);
        return null;
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
