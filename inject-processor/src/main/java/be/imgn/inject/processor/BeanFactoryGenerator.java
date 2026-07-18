package be.imgn.inject.processor;

import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

/** Emits a {@code Factory} subclass for a bean with constructor injection. */
final class BeanFactoryGenerator {

    private static final ClassName FACTORY = ClassName.get("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName CONSTRUCTOR_IP =
            ClassName.get("be.imgn.inject.context", "ConstructorInjectionPoint");
    private static final ClassName PARAMETER = ClassName.get("be.imgn.inject.context", "Parameter");
    private static final ClassName ANNOTATION_METADATA = ClassName.get("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    private BeanFactoryGenerator() {}

    static JavaFile generate(BeanModel model) {
        TypeSpec type = TypeSpec.classBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, model.beanType()))
                .addMethod(constructor(model))
                .addMethod(constructorInjectionPoint(model))
                .addMethod(createInstance(model))
                .build();
        return JavaFile.builder(model.packageName(), type).build();
    }

    private static MethodSpec constructor(BeanModel model) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("super($T.class)", model.beanType())
                .build();
    }

    private static MethodSpec constructorInjectionPoint(BeanModel model) {
        CodeBlock parameters = model.constructorParameters().stream()
                .map(parameter ->
                        CodeBlock.of("new $T($S, $T.class)", PARAMETER, parameter.name(), parameter.rawType()))
                .collect(CodeBlock.joining(", "));
        return MethodSpec.methodBuilder("constructorInjectionPoint")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(CONSTRUCTOR_IP)
                .addStatement(
                        "return new $T(this, $T.of($L), $T.empty())",
                        CONSTRUCTOR_IP,
                        LIST,
                        parameters,
                        ANNOTATION_METADATA)
                .build();
    }

    private static MethodSpec createInstance(BeanModel model) {
        CodeBlock arguments = IntStream.range(0, model.constructorParameters().size())
                .mapToObj(index -> CodeBlock.of("getInstanceForConstructor($L, context)", index))
                .collect(CodeBlock.joining(", "));
        return MethodSpec.methodBuilder("createInstance")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(model.beanType())
                .addParameter(CONTEXT, "context")
                .addStatement("return new $T($L)", model.beanType(), arguments)
                .build();
    }
}
