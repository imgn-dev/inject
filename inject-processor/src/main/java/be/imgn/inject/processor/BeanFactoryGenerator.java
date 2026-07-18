package be.imgn.inject.processor;

import java.util.List;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/** Emits a {@code Factory} subclass for a bean with constructor, field, and method injection. */
final class BeanFactoryGenerator {

    private static final ClassName FACTORY = ClassName.get("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName CONSTRUCTOR_IP =
            ClassName.get("be.imgn.inject.context", "ConstructorInjectionPoint");
    private static final ClassName FIELD_IP = ClassName.get("be.imgn.inject.context", "FieldInjectionPoint");
    private static final ClassName METHOD_IP = ClassName.get("be.imgn.inject.context", "MethodInjectionPoint");
    private static final ClassName PARAMETER = ClassName.get("be.imgn.inject.context", "Parameter");
    private static final ClassName ANNOTATION_METADATA = ClassName.get("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    private BeanFactoryGenerator() {}

    static JavaFile generate(BeanModel model) {
        TypeSpec.Builder type = TypeSpec.classBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, model.beanType()))
                .addMethod(constructor(model))
                .addMethod(constructorInjectionPoint(model))
                .addMethod(createInstance(model));
        if (!model.fields().isEmpty()) {
            type.addMethod(fieldInjectionPoints(model)).addMethod(injectFields(model));
        }
        if (!model.methods().isEmpty()) {
            type.addMethod(methodInjectionPoints(model)).addMethod(injectMethods(model));
        }
        return JavaFile.builder(model.packageName(), type.build()).build();
    }

    private static MethodSpec constructor(BeanModel model) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("super($T.class)", model.beanType())
                .build();
    }

    private static MethodSpec constructorInjectionPoint(BeanModel model) {
        CodeBlock parameters = parameterList(model.constructorParameters());
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

    private static MethodSpec fieldInjectionPoints(BeanModel model) {
        CodeBlock points = model.fields().stream()
                .map(field -> CodeBlock.of(
                        "new $T(this, $S, $T.class, $T.empty())",
                        FIELD_IP,
                        field.name(),
                        field.rawType(),
                        ANNOTATION_METADATA))
                .collect(CodeBlock.joining(", "));
        return MethodSpec.methodBuilder("fieldInjectionPoints")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(LIST, FIELD_IP))
                .addStatement("return $T.of($L)", LIST, points)
                .build();
    }

    private static MethodSpec injectFields(BeanModel model) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("injectFields")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(model.beanType(), "instance")
                .addParameter(CONTEXT, "context");
        for (int index = 0; index < model.fields().size(); index++) {
            builder.addStatement(
                    "instance.$L = getInstanceForField($L, context)",
                    model.fields().get(index).name(),
                    index);
        }
        return builder.build();
    }

    private static MethodSpec methodInjectionPoints(BeanModel model) {
        CodeBlock points = model.methods().stream()
                .map(method -> CodeBlock.of(
                        "new $T(this, $S, $T.of($L), $T.empty())",
                        METHOD_IP,
                        method.name(),
                        LIST,
                        parameterList(method.parameters()),
                        ANNOTATION_METADATA))
                .collect(CodeBlock.joining(", "));
        return MethodSpec.methodBuilder("methodInjectionPoints")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(LIST, METHOD_IP))
                .addStatement("return $T.of($L)", LIST, points)
                .build();
    }

    private static MethodSpec injectMethods(BeanModel model) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("injectMethods")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(model.beanType(), "instance")
                .addParameter(CONTEXT, "context");
        for (int index = 0; index < model.methods().size(); index++) {
            int methodIndex = index;
            MethodModel method = model.methods().get(index);
            CodeBlock arguments = IntStream.range(0, method.parameters().size())
                    .mapToObj(paramIndex ->
                            CodeBlock.of("getInstanceForMethod($L, $L, context)", methodIndex, paramIndex))
                    .collect(CodeBlock.joining(", "));
            builder.addStatement("instance.$L($L)", method.name(), arguments);
        }
        return builder.build();
    }

    private static CodeBlock parameterList(List<ParameterModel> parameters) {
        return parameters.stream()
                .map(parameter ->
                        CodeBlock.of("new $T($S, $T.class)", PARAMETER, parameter.name(), parameter.rawType()))
                .collect(CodeBlock.joining(", "));
    }
}
