package be.imgn.inject.processor;

import java.util.ArrayList;
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

/** Emits a {@code Factory} subclass that produces an instance by calling a {@code @Provides} method. */
final class ProvidesFactoryGenerator {

    private static final ClassName FACTORY = ClassName.get("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName CONSTRUCTOR_IP =
            ClassName.get("be.imgn.inject.context", "ConstructorInjectionPoint");
    private static final ClassName PARAMETER = ClassName.get("be.imgn.inject.context", "Parameter");
    private static final ClassName ANNOTATION_METADATA = ClassName.get("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName KEY = ClassName.get("be.imgn.inject", "Key");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName CLASS = ClassName.get("java.lang", "Class");
    private static final ClassName ANNOTATION = ClassName.get("java.lang.annotation", "Annotation");

    private ProvidesFactoryGenerator() {}

    static JavaFile generate(ProvidesModel model) {
        TypeSpec.Builder type = TypeSpec.classBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, model.producedType()))
                .addMethod(constructor(model))
                .addMethod(createInstance(model));
        if (!model.isStatic() || !model.methodParameters().isEmpty()) {
            type.addMethod(constructorInjectionPoint(model));
        }
        if (model.scopeAnnotation() != null) {
            type.addMethod(scope(model.scopeAnnotation()));
        }
        if (!model.exposedTypes().isEmpty()) {
            type.addMethod(exposedKeys(model));
        }
        return JavaFile.builder(model.packageName(), type.build()).build();
    }

    private static MethodSpec constructor(ProvidesModel model) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("super($T.class)", model.producedType())
                .build();
    }

    private static MethodSpec constructorInjectionPoint(ProvidesModel model) {
        List<CodeBlock> parts = new ArrayList<>();
        if (!model.isStatic()) {
            parts.add(CodeBlock.of("new $T($S, $T.class)", PARAMETER, "host", model.hostType()));
        }
        model.methodParameters().stream()
                .map(ProvidesFactoryGenerator::parameter)
                .forEach(parts::add);
        CodeBlock parameters = parts.stream().collect(CodeBlock.joining(", "));
        return MethodSpec.methodBuilder("constructorInjectionPoint")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(OPTIONAL, CONSTRUCTOR_IP))
                .addStatement(
                        "return $T.of(new $T(this, $T.of($L), $L))",
                        OPTIONAL,
                        CONSTRUCTOR_IP,
                        LIST,
                        parameters,
                        model.metadata())
                .build();
    }

    private static MethodSpec createInstance(ProvidesModel model) {
        int offset = model.isStatic() ? 0 : 1;
        CodeBlock arguments = IntStream.range(0, model.methodParameters().size())
                .mapToObj(index -> argument(model.methodParameters().get(index), index + offset))
                .collect(CodeBlock.joining(", "));
        MethodSpec.Builder builder = MethodSpec.methodBuilder("createInstance")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(model.producedType())
                .addParameter(CONTEXT, "context");
        if (model.isStatic()) {
            builder.addStatement("return $T.$L($L)", model.hostType(), model.methodName(), arguments);
        } else {
            builder.addStatement("$T host = getInstanceForConstructor(0, context)", model.hostType())
                    .addStatement("return host.$L($L)", model.methodName(), arguments);
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

    private static MethodSpec exposedKeys(ProvidesModel model) {
        RenderedQualifier qualifier = model.qualifier();
        CodeBlock keys = model.exposedTypes().stream()
                .map(type -> qualifier == null
                        ? CodeBlock.of("$T.of($T.class)", KEY, type)
                        : CodeBlock.of("$T.of($T.class, $L)", KEY, type, qualifier.expression()))
                .collect(CodeBlock.joining(", "));
        TypeName returnType = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(KEY, WildcardTypeName.subtypeOf(Object.class)));
        return MethodSpec.methodBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($L)", LIST, keys)
                .build();
    }

    private static CodeBlock argument(ParameterModel parameter, int index) {
        CodeBlock pluginInitializer = parameter.pluginInitializer();
        return pluginInitializer != null ? pluginInitializer : resolve(parameter.isProvider(), index);
    }

    private static CodeBlock resolve(boolean isProvider, int index) {
        return isProvider
                ? CodeBlock.of("getProviderForConstructor($L, context)", index)
                : CodeBlock.of("getInstanceForConstructor($L, context)", index);
    }

    private static CodeBlock parameter(ParameterModel parameter) {
        RenderedQualifier qualifier = parameter.qualifier();
        return CodeBlock.of(
                "new $T($S, $T.class, $L, $L, $L, $L)",
                PARAMETER,
                parameter.name(),
                parameter.rawType(),
                Qualifiers.markerArgument(qualifier),
                Qualifiers.instanceArgument(qualifier),
                parameter.metadata(),
                parameter.isProvider());
    }
}
