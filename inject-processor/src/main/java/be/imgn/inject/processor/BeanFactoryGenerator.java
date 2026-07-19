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
import com.palantir.javapoet.WildcardTypeName;

/** Emits a {@code Factory} subclass with constructor, field, and method injection, qualifiers, and scope. */
final class BeanFactoryGenerator {

    private static final ClassName FACTORY = ClassName.get("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName CONSTRUCTOR_IP =
            ClassName.get("be.imgn.inject.context", "ConstructorInjectionPoint");
    private static final ClassName FIELD_IP = ClassName.get("be.imgn.inject.context", "FieldInjectionPoint");
    private static final ClassName METHOD_IP = ClassName.get("be.imgn.inject.context", "MethodInjectionPoint");
    private static final ClassName PARAMETER = ClassName.get("be.imgn.inject.context", "Parameter");
    private static final ClassName ANNOTATION_METADATA = ClassName.get("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName KEY = ClassName.get("be.imgn.inject", "Key");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName CLASS = ClassName.get("java.lang", "Class");
    private static final ClassName ANNOTATION = ClassName.get("java.lang.annotation", "Annotation");

    private BeanFactoryGenerator() {}

    static JavaFile generate(BeanModel model) {
        TypeSpec.Builder type = TypeSpec.classBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, model.beanType()))
                .addMethod(constructor(model))
                .addMethod(constructorInjectionPoint(model))
                .addMethod(createInstance(model));
        if (!model.fields().isEmpty()) {
            type.addMethod(fieldInjectionPoints(model));
        }
        if (!model.fields().isEmpty() || !model.pluginFields().isEmpty()) {
            type.addMethod(injectFields(model));
        }
        if (!model.methods().isEmpty()) {
            type.addMethod(methodInjectionPoints(model)).addMethod(injectMethods(model));
        }
        if (model.scopeAnnotation() != null) {
            type.addMethod(scope(model.scopeAnnotation()));
        }
        if (model.beanQualifier() != null) {
            type.addMethod(exposedKeys(model, model.beanQualifier()));
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
        return MethodSpec.methodBuilder("constructorInjectionPoint")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(CONSTRUCTOR_IP)
                .addStatement(
                        "return new $T(this, $T.of($L), $T.empty())",
                        CONSTRUCTOR_IP,
                        LIST,
                        parameterList(model.constructorParameters()),
                        ANNOTATION_METADATA)
                .build();
    }

    private static MethodSpec createInstance(BeanModel model) {
        CodeBlock arguments = IntStream.range(0, model.constructorParameters().size())
                .mapToObj(index ->
                        resolve(model.constructorParameters().get(index).isProvider(), "Constructor", index))
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
        CodeBlock points =
                model.fields().stream().map(BeanFactoryGenerator::fieldPoint).collect(CodeBlock.joining(", "));
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
            FieldModel field = model.fields().get(index);
            builder.addStatement("instance.$L = $L", field.name(), resolve(field.isProvider(), "Field", index));
        }
        for (PluginField pluginField : model.pluginFields()) {
            builder.addStatement("instance.$L = $L", pluginField.name(), pluginField.initializer());
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
                            resolveMethod(method.parameters().get(paramIndex).isProvider(), methodIndex, paramIndex))
                    .collect(CodeBlock.joining(", "));
            builder.addStatement("instance.$L($L)", method.name(), arguments);
        }
        return builder.build();
    }

    private static MethodSpec scope(ClassName scopeAnnotation) {
        TypeName returnType = ParameterizedTypeName.get(
                OPTIONAL, ParameterizedTypeName.get(CLASS, WildcardTypeName.subtypeOf(ANNOTATION)));
        return MethodSpec.methodBuilder("scope")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.class)", OPTIONAL, scopeAnnotation)
                .build();
    }

    private static MethodSpec exposedKeys(BeanModel model, CodeBlock beanQualifier) {
        TypeName returnType = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(KEY, WildcardTypeName.subtypeOf(Object.class)));
        return MethodSpec.methodBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.of($T.class, $L))", LIST, KEY, model.beanType(), beanQualifier)
                .build();
    }

    private static CodeBlock resolve(boolean isProvider, String kind, int index) {
        return isProvider
                ? CodeBlock.of("getProviderFor$L($L, context)", kind, index)
                : CodeBlock.of("getInstanceFor$L($L, context)", kind, index);
    }

    private static CodeBlock resolveMethod(boolean isProvider, int methodIndex, int paramIndex) {
        return isProvider
                ? CodeBlock.of("getProviderForMethod($L, $L, context)", methodIndex, paramIndex)
                : CodeBlock.of("getInstanceForMethod($L, $L, context)", methodIndex, paramIndex);
    }

    private static CodeBlock parameterList(List<ParameterModel> parameters) {
        return parameters.stream().map(BeanFactoryGenerator::parameter).collect(CodeBlock.joining(", "));
    }

    private static CodeBlock parameter(ParameterModel parameter) {
        CodeBlock qualifier = parameter.qualifier();
        if (!parameter.isProvider()) {
            return qualifier == null
                    ? CodeBlock.of("new $T($S, $T.class)", PARAMETER, parameter.name(), parameter.rawType())
                    : CodeBlock.of(
                            "new $T($S, $T.class, $L)", PARAMETER, parameter.name(), parameter.rawType(), qualifier);
        }
        return qualifier == null
                ? CodeBlock.of("new $T($S, $T.class, true)", PARAMETER, parameter.name(), parameter.rawType())
                : CodeBlock.of(
                        "new $T($S, $T.class, $L, true)", PARAMETER, parameter.name(), parameter.rawType(), qualifier);
    }

    private static CodeBlock fieldPoint(FieldModel field) {
        CodeBlock qualifier = field.qualifier();
        if (!field.isProvider()) {
            return qualifier == null
                    ? CodeBlock.of(
                            "new $T(this, $S, $T.class, $T.empty())",
                            FIELD_IP,
                            field.name(),
                            field.rawType(),
                            ANNOTATION_METADATA)
                    : CodeBlock.of(
                            "new $T(this, $S, $T.class, $L, $T.empty())",
                            FIELD_IP,
                            field.name(),
                            field.rawType(),
                            qualifier,
                            ANNOTATION_METADATA);
        }
        CodeBlock qualifierArgument = qualifier == null ? CodeBlock.of("null") : qualifier;
        return CodeBlock.of(
                "new $T(this, $S, $T.class, $L, $T.empty(), true)",
                FIELD_IP,
                field.name(),
                field.rawType(),
                qualifierArgument,
                ANNOTATION_METADATA);
    }
}
