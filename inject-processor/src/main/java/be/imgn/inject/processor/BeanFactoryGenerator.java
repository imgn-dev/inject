package be.imgn.inject.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import java.util.List;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

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
    private static final ClassName CONTRIBUTION = ClassName.get("be.imgn.inject.context", "Contribution");
    private static final ClassName TYPES = ClassName.get("be.imgn.inject", "Types");
    private static final ClassName STRING_CLASS = ClassName.get("java.lang", "String");

    private BeanFactoryGenerator() {}

    static JavaFile generate(BeanModel model) {
        TypeSpec.Builder type = TypeSpec.classBuilder(model.factorySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, model.beanType()))
                .addMethod(constructor(model))
                .addMethod(createInstance(model))
                .addMethod(annotationMetadata(model));
        if (!model.constructorParameters().isEmpty()) {
            type.addMethod(constructorInjectionPoint(model));
        }
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
        if (model.postConstruct() != null) {
            type.addMethod(postConstruct(model));
        }
        if (model.preDestroy() != null) {
            type.addMethod(preDestroy(model)).addMethod(hasPreDestroy());
        }
        if (model.interceptsAnnotation() != null) {
            type.addMethod(intercepts(model.interceptsAnnotation()));
        }
        if (model.contribution() != null) {
            type.addMethod(emptyExposedKeys()).addMethod(contributionMethod(model.contribution()));
        } else if (!model.exposedTypes().isEmpty()) {
            type.addMethod(exposedKeys(model));
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
                .returns(ParameterizedTypeName.get(OPTIONAL, CONSTRUCTOR_IP))
                .addStatement(
                        "return $T.of(new $T(this, $T.of($L), $L))",
                        OPTIONAL,
                        CONSTRUCTOR_IP,
                        LIST,
                        parameterList(model.constructorParameters()),
                        model.constructorMetadata())
                .build();
    }

    private static MethodSpec annotationMetadata(BeanModel model) {
        return MethodSpec.methodBuilder("annotationMetadata")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ANNOTATION_METADATA)
                .addStatement("return $L", model.definitionMetadata())
                .build();
    }

    private static MethodSpec createInstance(BeanModel model) {
        CodeBlock arguments = IntStream.range(0, model.constructorParameters().size())
                .mapToObj(index -> argument(model.constructorParameters().get(index), "Constructor", index))
                .collect(CodeBlock.joining(", "));
        InterceptionModel interception = model.interception();
        CodeBlock construction = interception == null
                ? CodeBlock.of("new $T($L)", model.beanType(), arguments)
                : CodeBlock.of(
                        "new $L(context$L)",
                        interception.proxySimpleName(),
                        arguments.isEmpty() ? CodeBlock.of("") : CodeBlock.of(", $L", arguments));
        return MethodSpec.methodBuilder("createInstance")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(model.beanType())
                .addParameter(CONTEXT, "context")
                .addStatement("return $L", construction)
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
            builder.addStatement(
                    "instance.$L = $L",
                    field.name(),
                    resolve(field.isProvider(), field.typeArgument(), field.erasure(), "Field", index));
        }
        for (PluginField pluginField : model.pluginFields()) {
            builder.addStatement("instance.$L = $L", pluginField.name(), pluginField.initializer());
        }
        return builder.build();
    }

    private static MethodSpec methodInjectionPoints(BeanModel model) {
        CodeBlock points = model.methods().stream()
                .map(method -> CodeBlock.of(
                        "new $T(this, $S, $T.of($L), $L)",
                        METHOD_IP,
                        method.name(),
                        LIST,
                        parameterList(method.parameters()),
                        method.metadata()))
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
                    .mapToObj(
                            paramIndex -> methodArgument(method.parameters().get(paramIndex), methodIndex, paramIndex))
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

    private static MethodSpec intercepts(ClassName annotation) {
        TypeName returnType = ParameterizedTypeName.get(
                OPTIONAL, ParameterizedTypeName.get(CLASS, WildcardTypeName.subtypeOf(ANNOTATION)));
        return MethodSpec.methodBuilder("intercepts")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.of($T.class)", OPTIONAL, annotation)
                .build();
    }

    private static MethodSpec exposedKeys(BeanModel model) {
        RenderedQualifier qualifier = model.beanQualifier();
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

    private static MethodSpec postConstruct(BeanModel model) {
        return MethodSpec.methodBuilder("postConstruct")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(model.beanType(), "instance")
                .addStatement("instance.$L()", model.postConstruct())
                .build();
    }

    private static MethodSpec preDestroy(BeanModel model) {
        return MethodSpec.methodBuilder("preDestroy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(model.beanType(), "instance")
                .addStatement("instance.$L()", model.preDestroy())
                .build();
    }

    private static MethodSpec hasPreDestroy() {
        return MethodSpec.methodBuilder("hasPreDestroy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.BOOLEAN)
                .addStatement("return true")
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

    private static CodeBlock argument(ParameterModel parameter, String kind, int index) {
        CodeBlock pluginInitializer = parameter.pluginInitializer();
        return pluginInitializer != null
                ? pluginInitializer
                : resolve(parameter.isProvider(), parameter.typeArgument(), parameter.erasure(), kind, index);
    }

    private static CodeBlock methodArgument(ParameterModel parameter, int methodIndex, int paramIndex) {
        CodeBlock pluginInitializer = parameter.pluginInitializer();
        return pluginInitializer != null
                ? pluginInitializer
                : resolveMethod(
                        parameter.isProvider(), parameter.typeArgument(), parameter.erasure(), methodIndex, paramIndex);
    }

    /**
     * {@return a resolution call: the explicit type argument gives it an exact static type, so the call selects the
     * right overload, and the class literal is checked at runtime against the injection point and the resolved value}
     *
     * <p>A primitive dependency is resolved as its wrapper and cast back, so that {@code m(int)} is still chosen over
     * {@code m(Integer)}.
     */
    static CodeBlock resolution(
            boolean isProvider, TypeName typeArgument, TypeName erasure, String kind, CodeBlock indexes) {
        CodeBlock call = CodeBlock.of(
                "this.<$T>get$LFor$L($L, $T.class, context)",
                typeArgument,
                isProvider ? "Provider" : "Instance",
                kind,
                indexes,
                erasure);
        return erasure.isPrimitive() && !isProvider ? CodeBlock.of("($T) $L", erasure, call) : call;
    }

    private static CodeBlock resolve(
            boolean isProvider, TypeName typeArgument, TypeName erasure, String kind, int index) {
        return resolution(isProvider, typeArgument, erasure, kind, CodeBlock.of("$L", index));
    }

    private static CodeBlock resolveMethod(
            boolean isProvider, TypeName typeArgument, TypeName erasure, int methodIndex, int paramIndex) {
        return resolution(isProvider, typeArgument, erasure, "Method", CodeBlock.of("$L, $L", methodIndex, paramIndex));
    }

    private static CodeBlock parameterList(List<ParameterModel> parameters) {
        return parameters.stream().map(BeanFactoryGenerator::parameter).collect(CodeBlock.joining(", "));
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

    private static CodeBlock fieldPoint(FieldModel field) {
        RenderedQualifier qualifier = field.qualifier();
        return CodeBlock.of(
                "new $T(this, $S, $L, $L, $L, $L, $L)",
                FIELD_IP,
                field.name(),
                field.type(),
                Qualifiers.markerArgument(qualifier),
                Qualifiers.instanceArgument(qualifier),
                field.metadata(),
                field.isProvider());
    }
}
