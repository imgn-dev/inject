package be.imgn.inject.processor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
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

import be.imgn.inject.Inject;
import be.imgn.inject.Provider;

/**
 * Annotation processor that generates one zero-reflection {@code Factory} subclass per injectable class. Handles
 * {@code @Inject} constructors, fields, and methods, qualifying and scoping annotations, and {@code Provider<T>}
 * injection. {@code @Provides}, graph validation, and service registration are added in later tasks.
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
        Set<TypeElement> beans = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Inject.class)) {
            if (element.getEnclosingElement() instanceof TypeElement bean) {
                beans.add(bean);
            }
        }
        for (TypeElement bean : beans) {
            generateFactory(bean);
        }
        return false;
    }

    private void generateFactory(TypeElement bean) {
        try {
            BeanFactoryGenerator.generate(buildModel(bean)).writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write factory: " + e.getMessage(), bean);
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
        String packageName = elements.getPackageOf(bean).getQualifiedName().toString();
        return new BeanModel(
                packageName,
                GeneratedNames.factoryName(enclosingChain(bean)),
                ClassName.get(bean),
                constructorParameters,
                List.copyOf(fields),
                List.copyOf(methods),
                Annotations.scope(bean).orElse(null),
                Annotations.qualifier(bean, elements).orElse(null));
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
        if (type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement typeElement
                && typeElement.getQualifiedName().contentEquals(PROVIDER)
                && declared.getTypeArguments().size() == 1) {
            TypeMirror inner = declared.getTypeArguments().get(0);
            return new Dependency(TypeName.get(types.erasure(inner)), true);
        }
        return new Dependency(TypeName.get(types.erasure(type)), false);
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
