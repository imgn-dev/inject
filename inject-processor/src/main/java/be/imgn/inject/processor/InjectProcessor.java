package be.imgn.inject.processor;

import java.io.IOException;
import java.io.Writer;
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
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
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
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Provider;
import be.imgn.inject.Provides;
import be.imgn.inject.context.Factory;
import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;

/**
 * Annotation processor that generates one zero-reflection {@code Factory} subclass per injectable class. Handles
 * {@code @Inject} constructors, fields, and methods, qualifying and scoping annotations, {@code Provider<T>} injection,
 * default factories for undeclared in-compilation dependencies, {@code @Provides} methods, compile-time
 * cycle/missing-dependency/accessibility validation, {@code ServiceLoader} registration, and a plugin SPI.
 */
@SupportedAnnotationTypes("*")
public final class InjectProcessor extends AbstractProcessor {

    private static final String PROVIDER = Provider.class.getCanonicalName();
    private static final String FACTORY_SERVICE = "META-INF/services/" + Factory.class.getName();

    private List<InjectPlugin> plugins = List.of();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        List<InjectPlugin> loaded = new ArrayList<>();
        ServiceLoader.load(InjectPlugin.class, getClass().getClassLoader()).forEach(loaded::add);
        this.plugins = List.copyOf(loaded);
    }

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

        Map<TypeElement, BeanModel> toGenerate = new LinkedHashMap<>();
        Map<TypeElement, List<TypeElement>> edges = new LinkedHashMap<>();
        List<TypeElement> seeds = new ArrayList<>();
        for (TypeElement bean : beanTypes) {
            if (!validateBean(bean)) {
                continue;
            }
            toGenerate.put(bean, buildModel(bean));
            // Cycle edges exclude Provider-wrapped dependencies: a Provider is injected un-resolved, so it
            // legitimately breaks a cycle at runtime and must not be reported as one at compile time.
            edges.put(bean, collectDependencies(bean, types, false));
            seeds.addAll(collectDependencies(bean, types, true));
        }

        List<ProvidesModel> providesModels = gatherProvides(roundEnv);
        seeds.addAll(providesDependencies(roundEnv, types));
        planDefaultFactories(toGenerate, edges, roundEnv, seeds);

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

        // Duplicate bindings that arise WITHIN one compilation fall through to the runtime
        // DuplicateBindingException. Cross-module duplicates are undetectable at compile time under the
        // ServiceLoader architecture, so runtime detection is the single, consistent safety net.
        List<String> factoryNames = new ArrayList<>();
        for (BeanModel model : toGenerate.values()) {
            write(model);
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
        for (ProvidesModel model : providesModels) {
            writeProvides(model);
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
        registerFactories(factoryNames);
        return false;
    }

    private void registerFactories(List<String> factoryNames) {
        if (factoryNames.isEmpty()) {
            return;
        }
        if (serviceFileExistsOnSourcePath()) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR,
                            "A hand-authored " + FACTORY_SERVICE
                                    + " exists on the source path; the processor owns this file. Remove it.");
            return;
        }
        try {
            FileObject file =
                    processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", FACTORY_SERVICE);
            try (Writer writer = file.openWriter()) {
                for (String name : factoryNames) {
                    writer.write(name);
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write factory service file: " + e.getMessage());
        }
        String directives = "For the module path, declare in module-info.java:\n    provides " + Factory.class.getName()
                + " with\n        " + String.join(",\n        ", factoryNames) + ";";
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, directives);
    }

    private boolean serviceFileExistsOnSourcePath() {
        try {
            processingEnv
                    .getFiler()
                    .getResource(StandardLocation.SOURCE_PATH, "", FACTORY_SERVICE)
                    .openInputStream()
                    .close();
            return true;
        } catch (Exception e) {
            return false;
        }
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
            addParameterDependencies(method, types, dependencies, true);
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
        Deque<TypeElement> queue = new ArrayDeque<>(extraRoots);
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
                pluginFields(bean),
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
                List.of(),
                Annotations.scope(type).orElse(null),
                Annotations.qualifier(type, processingEnv.getElementUtils()).orElse(null));
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
                error(
                        "Field " + field.getSimpleName() + " in " + bean.getQualifiedName()
                                + " annotated with an injecting annotation must not be private, static, or final.",
                        field);
                continue;
            }
            Optional<CodeBlock> initializer = pluginInitializer(field, bean);
            if (initializer.isEmpty()) {
                error(
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
        InjectionSite site = new InjectionSite(field, bean, field.asType(), processingEnv);
        for (InjectPlugin plugin : plugins) {
            Optional<CodeBlock> initializer = plugin.fieldInitializer(site);
            if (initializer.isPresent()) {
                return initializer;
            }
        }
        return Optional.empty();
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

    private String packageOf(TypeElement type) {
        return processingEnv
                .getElementUtils()
                .getPackageOf(type)
                .getQualifiedName()
                .toString();
    }

    private List<TypeElement> collectDependencies(TypeElement bean, Types types, boolean includeProviderTargets) {
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

    private static void addParameterDependencies(
            ExecutableElement executable, Types types, List<TypeElement> result, boolean includeProviderTargets) {
        for (VariableElement parameter : executable.getParameters()) {
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

    private boolean validateBean(TypeElement bean) {
        boolean valid = true;
        Optional<ExecutableElement> constructor = injectConstructor(bean);
        if (constructor.isPresent() && constructor.get().getModifiers().contains(Modifier.PRIVATE)) {
            error("@Inject constructor of " + bean.getQualifiedName() + " must not be private.", constructor.get());
            valid = false;
        }
        if (constructor.isEmpty() && !hasAccessibleNoArgConstructor(bean)) {
            error(
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
                error(
                        "@Inject " + enclosed.getSimpleName() + " in " + bean.getQualifiedName()
                                + " must not be private or static.",
                        enclosed);
                valid = false;
            }
        }
        return valid;
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
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
