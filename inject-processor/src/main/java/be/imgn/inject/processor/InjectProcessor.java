package be.imgn.inject.processor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import be.imgn.inject.Inject;

/**
 * Annotation processor that generates one zero-reflection {@code Factory} subclass per injectable class. Currently
 * handles {@code @Inject} constructors; field/method injection, qualifiers, scopes, {@code @Provides}, validation, and
 * service registration are added in later tasks.
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
            if (element.getKind() == ElementKind.CONSTRUCTOR) {
                beans.add((TypeElement) element.getEnclosingElement());
            }
        }
        for (TypeElement bean : beans) {
            generateFactory(bean);
        }
        return false;
    }

    private void generateFactory(TypeElement bean) {
        BeanModel model = buildModel(bean);
        try {
            BeanFactoryGenerator.generate(model).writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write factory: " + e.getMessage(), bean);
        }
    }

    private BeanModel buildModel(TypeElement bean) {
        ExecutableElement constructor = injectConstructor(bean);
        Types types = processingEnv.getTypeUtils();
        List<ParameterModel> parameters = constructor.getParameters().stream()
                .map(parameter -> new ParameterModel(
                        parameter.getSimpleName().toString(), TypeName.get(types.erasure(parameter.asType()))))
                .toList();
        String packageName = processingEnv
                .getElementUtils()
                .getPackageOf(bean)
                .getQualifiedName()
                .toString();
        return new BeanModel(
                packageName, GeneratedNames.factoryName(enclosingChain(bean)), ClassName.get(bean), parameters);
    }

    private static ExecutableElement injectConstructor(TypeElement bean) {
        for (Element enclosed : bean.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR && enclosed.getAnnotation(Inject.class) != null) {
                return (ExecutableElement) enclosed;
            }
        }
        throw new IllegalStateException("No @Inject constructor on " + bean.getQualifiedName());
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
