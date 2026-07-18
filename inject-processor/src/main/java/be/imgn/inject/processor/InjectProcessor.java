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
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import be.imgn.inject.Inject;

/**
 * Annotation processor that generates one zero-reflection {@code Factory} subclass per injectable class. Handles
 * {@code @Inject} constructors, fields, and methods; qualifiers, scopes, {@code @Provides}, validation, and service
 * registration are added in later tasks.
 */
@SupportedAnnotationTypes("*")
public final class InjectProcessor extends AbstractProcessor {

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
        List<ParameterModel> constructorParameters = injectConstructor(bean)
                .map(constructor -> parameters(constructor, types))
                .orElse(List.of());
        List<FieldModel> fields = new ArrayList<>();
        List<MethodModel> methods = new ArrayList<>();
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getAnnotation(Inject.class) == null) {
                continue;
            }
            if (enclosed.getKind() == ElementKind.FIELD) {
                fields.add(new FieldModel(enclosed.getSimpleName().toString(), rawType(enclosed.asType(), types)));
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                methods.add(new MethodModel(method.getSimpleName().toString(), parameters(method, types)));
            }
        }
        String packageName = processingEnv
                .getElementUtils()
                .getPackageOf(bean)
                .getQualifiedName()
                .toString();
        return new BeanModel(
                packageName,
                GeneratedNames.factoryName(enclosingChain(bean)),
                ClassName.get(bean),
                constructorParameters,
                List.copyOf(fields),
                List.copyOf(methods));
    }

    private static Optional<ExecutableElement> injectConstructor(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR && enclosed.getAnnotation(Inject.class) != null) {
                return Optional.of((ExecutableElement) enclosed);
            }
        }
        return Optional.empty();
    }

    private static List<ParameterModel> parameters(ExecutableElement executable, Types types) {
        return executable.getParameters().stream()
                .map(parameter ->
                        new ParameterModel(parameter.getSimpleName().toString(), rawType(parameter.asType(), types)))
                .toList();
    }

    private static TypeName rawType(TypeMirror mirror, Types types) {
        return TypeName.get(types.erasure(mirror));
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
}
