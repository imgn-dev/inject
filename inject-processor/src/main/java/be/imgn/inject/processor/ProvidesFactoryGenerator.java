package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.gaia.processing.source.JavaFile;
import be.imgn.gaia.processing.source.Method;
import be.imgn.gaia.processing.source.ParameterizedTypeName;
import be.imgn.gaia.processing.source.Type;
import be.imgn.gaia.processing.source.TypeName;
import be.imgn.gaia.processing.source.WildcardTypeName;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

/** Emits a {@code Factory} subclass that produces an instance by calling a {@code @Provides} method. */
final class ProvidesFactoryGenerator {

    private static final ClassName FACTORY = ClassName.of("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.of("be.imgn.inject.context", "Context");
    private static final ClassName CONSTRUCTOR_IP = ClassName.of("be.imgn.inject.context", "ConstructorInjectionPoint");
    private static final ClassName PARAMETER = ClassName.of("be.imgn.inject.context", "Parameter");
    private static final ClassName ANNOTATION_METADATA = ClassName.of("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName KEY = ClassName.of("be.imgn.inject", "Key");
    private static final ClassName LIST = ClassName.of("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.of("java.util", "Optional");
    private static final ClassName CLASS = ClassName.of("java.lang", "Class");
    private static final ClassName ANNOTATION = ClassName.of("java.lang.annotation", "Annotation");
    private static final ClassName CONTRIBUTION = ClassName.of("be.imgn.inject.context", "Contribution");
    private static final ClassName TYPES = ClassName.of("be.imgn.inject", "Types");
    private static final ClassName STRING_CLASS = ClassName.of("java.lang", "String");

    private ProvidesFactoryGenerator() {}

    static JavaFile generate(ProvidesModel model) {
        Type.Builder type = Type.newClassBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.of(FACTORY, model.producedType()))
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
        return JavaFile.newBuilder(model.packageName(), type.build()).build();
    }

    private static Method constructor(ProvidesModel model) {
        return Method.newConstructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("super($T.class)", model.producedType())
                .build();
    }

    private static Method annotationMetadata(ProvidesModel model) {
        return Method.newBuilder("annotationMetadata")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ANNOTATION_METADATA)
                .addStatement("return $L", model.definitionMetadata())
                .build();
    }

    private static Method constructorInjectionPoint(ProvidesModel model) {
        List<CodeBlock> parts = new ArrayList<>();
        if (!model.isStatic()) {
            parts.add(CodeBlock.of("new $T($S, $T.class)", PARAMETER, "host", model.hostType()));
        }
        model.methodParameters().stream()
                .map(ProvidesFactoryGenerator::parameter)
                .forEach(parts::add);
        CodeBlock parameters = parts.stream().collect(CodeBlock.joining(", "));
        return Method.newBuilder("constructorInjectionPoint")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.of(OPTIONAL, CONSTRUCTOR_IP))
                .addStatement(
                        "return $T.of(new $T(this, $T.of($L), $L))",
                        OPTIONAL,
                        CONSTRUCTOR_IP,
                        LIST,
                        parameters,
                        model.metadata())
                .build();
    }

    private static Method createInstance(ProvidesModel model) {
        int offset = model.isStatic() ? 0 : 1;
        CodeBlock arguments = IntStream.range(0, model.methodParameters().size())
                .mapToObj(index -> argument(model.methodParameters().get(index), index + offset))
                .collect(CodeBlock.joining(", "));
        Method.Builder builder = Method.newBuilder("createInstance")
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

    private static Method scope(ClassName scopeAnnotation) {
        TypeName returnType = ParameterizedTypeName.of(
                OPTIONAL, ParameterizedTypeName.of(CLASS, WildcardTypeName.subtypeOf(ANNOTATION)));
        return Method.newBuilder("scope")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.class)", OPTIONAL, scopeAnnotation)
                .build();
    }

    private static Method exposedKeys(ProvidesModel model) {
        RenderedQualifier qualifier = model.qualifier();
        CodeBlock keys = model.exposedTypes().stream()
                .map(type -> qualifier == null
                        ? CodeBlock.of("$T.of($T.class)", KEY, type)
                        : CodeBlock.of("$T.of($T.class, $L)", KEY, type, qualifier.expression()))
                .collect(CodeBlock.joining(", "));
        TypeName returnType =
                ParameterizedTypeName.of(LIST, ParameterizedTypeName.of(KEY, WildcardTypeName.unbounded()));
        return Method.newBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($L)", LIST, keys)
                .build();
    }

    private static Method collectionExposedKeys(CodeBlock producedKeyType) {
        TypeName returnType =
                ParameterizedTypeName.of(LIST, ParameterizedTypeName.of(KEY, WildcardTypeName.unbounded()));
        return Method.newBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.of($L))", LIST, KEY, producedKeyType)
                .build();
    }

    private static Method emptyExposedKeys() {
        TypeName keys = ParameterizedTypeName.of(LIST, ParameterizedTypeName.of(KEY, WildcardTypeName.unbounded()));
        return Method.newBuilder("exposedKeys")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(keys)
                .addStatement("return $T.of()", LIST)
                .build();
    }

    private static Method contributionMethod(ContributionModel contribution) {
        CodeBlock collectionKey = contribution.mapKey() == null
                ? CodeBlock.of("$T.of($T.setOf($T.class))", KEY, TYPES, contribution.elementType())
                : CodeBlock.of(
                        "$T.of($T.mapOf($T.class, $T.class))", KEY, TYPES, STRING_CLASS, contribution.elementType());
        CodeBlock mapKey =
                contribution.mapKey() == null ? CodeBlock.of("null") : CodeBlock.of("$S", contribution.mapKey());
        return Method.newBuilder("contribution")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.of(OPTIONAL, CONTRIBUTION))
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

    private static Method isDefaultMethod() {
        return Method.newBuilder("isDefault")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(boolean.class)
                .addStatement("return true")
                .build();
    }
}
