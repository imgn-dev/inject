package be.imgn.inject.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

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
    private static final ClassName CONTRIBUTION = ClassName.get("be.imgn.inject.context", "Contribution");
    private static final ClassName TYPES = ClassName.get("be.imgn.inject", "Types");
    private static final ClassName STRING_CLASS = ClassName.get("java.lang", "String");

    private ProvidesFactoryGenerator() {}

    static JavaFile generate(ProvidesModel model) {
        TypeSpec.Builder type = TypeSpec.classBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, model.producedType()))
                .addMethod(constructor(model))
                .addMethod(createInstance(model))
                .addMethod(annotationMetadata(model));
        if (!model.isStatic() || !model.methodParameters().isEmpty()) {
            type.addMethod(constructorInjectionPoint(model));
        }
        if (model.scopeAnnotation() != null) {
            type.addMethod(scope(model.scopeAnnotation()));
        }
        if (model.contribution() != null) {
            type.addMethod(emptyExposedKeys()).addMethod(contributionMethod(model.contribution()));
        } else if (!model.exposedTypes().isEmpty()) {
            type.addMethod(exposedKeys(model));
        } else if (model.producedKeyType() != null) {
            type.addMethod(collectionExposedKeys(model.producedKeyType()));
        }
        if (model.isDefault()) {
            type.addMethod(isDefaultMethod());
        }
        return JavaFile.builder(model.packageName(), type.build()).build();
    }

    private static MethodSpec constructor(ProvidesModel model) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("super($T.class)", model.producedType())
                .build();
    }

    private static MethodSpec annotationMetadata(ProvidesModel model) {
        return MethodSpec.methodBuilder("annotationMetadata")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ANNOTATION_METADATA)
                .addStatement("return $L", model.definitionMetadata())
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
            builder.addStatement(
                            "$T host = this.<$T>getInstanceForConstructor(0, $T.class, context)",
                            model.hostType(),
                            model.hostType(),
                            model.hostType())
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

    private static MethodSpec collectionExposedKeys(CodeBlock producedKeyType) {
        TypeName returnType = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(KEY, WildcardTypeName.subtypeOf(Object.class)));
        return MethodSpec.methodBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.of($L))", LIST, KEY, producedKeyType)
                .build();
    }

    private static MethodSpec emptyExposedKeys() {
        TypeName keys = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(KEY, WildcardTypeName.subtypeOf(Object.class)));
        return MethodSpec.methodBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(keys)
                .addStatement("return $T.of()", LIST)
                .build();
    }

    private static MethodSpec contributionMethod(ContributionModel contribution) {
        CodeBlock collectionKey = contribution.mapKey() == null
                ? CodeBlock.of("$T.of($T.setOf($T.class))", KEY, TYPES, contribution.elementType())
                : CodeBlock.of(
                        "$T.of($T.mapOf($T.class, $T.class))", KEY, TYPES, STRING_CLASS, contribution.elementType());
        CodeBlock mapKey =
                contribution.mapKey() == null ? CodeBlock.of("null") : CodeBlock.of("$S", contribution.mapKey());
        return MethodSpec.methodBuilder("contribution")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(OPTIONAL, CONTRIBUTION))
                .addStatement("return $T.of(new $T($L, $L))", OPTIONAL, CONTRIBUTION, collectionKey, mapKey)
                .build();
    }

    private static CodeBlock argument(ParameterModel parameter, int index) {
        CodeBlock pluginInitializer = parameter.pluginInitializer();
        return pluginInitializer != null
                ? pluginInitializer
                : resolve(parameter.isProvider(), parameter.typeArgument(), parameter.erasure(), index);
    }

    /**
     * {@return a resolution call for a {@code @Provides} method parameter; see {@link BeanFactoryGenerator#resolution}}
     */
    private static CodeBlock resolve(boolean isProvider, TypeName typeArgument, TypeName erasure, int index) {
        return BeanFactoryGenerator.resolution(
                isProvider, typeArgument, erasure, "Constructor", CodeBlock.of("$L", index));
    }

    private static CodeBlock parameter(ParameterModel parameter) {
        RenderedQualifier qualifier = parameter.qualifier();
        return CodeBlock.of(
                "new $T($S, $L, $L, $L, $L, $L)",
                PARAMETER,
                parameter.name(),
                parameter.type(),
                Qualifiers.markerArgument(qualifier),
                Qualifiers.instanceArgument(qualifier),
                parameter.metadata(),
                parameter.isProvider());
    }

    private static MethodSpec isDefaultMethod() {
        return MethodSpec.methodBuilder("isDefault")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(boolean.class)
                .addStatement("return true")
                .build();
    }
}
