package be.imgn.inject.processor;

import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.gaia.processing.source.JavaFile;
import be.imgn.gaia.processing.source.Method;
import be.imgn.gaia.processing.source.ParameterizedTypeName;
import be.imgn.gaia.processing.source.Type;
import be.imgn.inject.Assisted;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import org.jspecify.annotations.Nullable;

/**
 * Emits an {@code @AssistedFactory} interface's implementation and its {@code Factory} registration: an impl class that
 * resolves container parameters through the {@code Context} and passes assisted parameters through verbatim, plus a
 * {@code Factory} subclass that constructs it.
 */
final class AssistedFactoryGenerator {
    private static final ClassName FACTORY = ClassName.of("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.of("be.imgn.inject.context", "Context");
    private static final ClassName ANNOTATION_METADATA = ClassName.of("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName KEY = ClassName.of("be.imgn.inject", "Key");
    private static final ClassName OBJECTS = ClassName.of("java.util", "Objects");
    private static final ClassName LIST = ClassName.of("java.util", "List");
    private static final ClassName SCOPED_PRODUCT = ClassName.of("be.imgn.inject.context", "ScopedProduct");

    private AssistedFactoryGenerator() {}

    static Generated generate(AssistedFactoryModel model, ModelFactory models, Elements elements) {
        TypeElement factoryInterface = model.factoryInterface();
        String packageName =
                elements.getPackageOf(factoryInterface).getQualifiedName().toString();
        ClassName interfaceType = ClassName.of(factoryInterface);
        List<String> chain = ModelFactory.enclosingChain(factoryInterface);
        ClassName implType = ClassName.of(packageName, GeneratedNames.implName(chain));
        String factorySimpleName = GeneratedNames.factoryName(chain);

        JavaFile implFile = JavaFile.newBuilder(packageName, implType(model, models, interfaceType, implType))
                .build();
        JavaFile factoryFile = JavaFile.newBuilder(
                        packageName, factoryType(model, models, factorySimpleName, interfaceType, implType, elements))
                .build();
        return new Generated(implFile, factoryFile);
    }

    private static Type implType(
            AssistedFactoryModel model, ModelFactory models, ClassName interfaceType, ClassName implType) {
        Type.Builder type = Type.newClassBuilder(implType)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(interfaceType)
                .addField(CONTEXT, "context", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(Method.newConstructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(CONTEXT, "context")
                        .addStatement("this.context = $T.requireNonNull(context, $S)", OBJECTS, "context")
                        .build());
        for (AssistedFactoryMethod method : model.methods()) {
            type.addMethod(createMethod(method, model.factoryInterface(), models));
        }
        return type.build();
    }

    private static Type factoryType(
            AssistedFactoryModel model,
            ModelFactory models,
            String factorySimpleName,
            ClassName interfaceType,
            ClassName implType,
            Elements elements) {
        TypeElement factoryInterface = model.factoryInterface();
        Type.Builder type = Type.newClassBuilder(factorySimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.of(FACTORY, interfaceType))
                .addMethod(Method.newConstructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("super($T.class)", interfaceType)
                        .build())
                .addMethod(Method.newBuilder("createInstance")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PROTECTED)
                        .returns(interfaceType)
                        .addParameter(CONTEXT, "context")
                        .addStatement("return new $T(context)", implType)
                        .build())
                .addMethod(Method.newBuilder("annotationMetadata")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ANNOTATION_METADATA)
                        .addStatement("return $L", Annotations.metadata(List.of(factoryInterface), elements))
                        .build());
        Map<String, CodeBlock> products = new LinkedHashMap<>();
        for (AssistedFactoryMethod method : model.methods()) {
            scopedProduct(method, factoryInterface, models)
                    .ifPresent(product -> products.putIfAbsent(product.toString(), product));
        }
        if (!products.isEmpty()) {
            type.addMethod(Method.newBuilder("scopedProducts")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PROTECTED)
                    .returns(ParameterizedTypeName.of(LIST, SCOPED_PRODUCT))
                    .addStatement(
                            "return $T.of($L)", LIST, products.values().stream().collect(CodeBlock.joining(", ")))
                    .build());
        }
        return type.build();
    }

    /** {@return the {@code ScopedProduct} expression of the method's product, if its recipe has a scope} */
    private static Optional<CodeBlock> scopedProduct(
            AssistedFactoryMethod method, TypeElement factoryInterface, ModelFactory models) {
        AssistedRecipe recipe = method.recipe();
        return models.recipeScope(recipe)
                .map(scope -> CodeBlock.of(
                        "new $T($T.of($L), $T.class, $S)",
                        SCOPED_PRODUCT,
                        KEY,
                        models.productType(recipe),
                        scope,
                        factoryInterface.getQualifiedName() + "."
                                + method.method().getSimpleName()));
    }

    private static Method createMethod(
            AssistedFactoryMethod factoryMethod, TypeElement factoryInterface, ModelFactory models) {
        Method.Builder method = Method.overriding(factoryMethod.method());
        AssistedRecipe recipe = factoryMethod.recipe();
        CodeBlock arguments = callArguments(factoryMethod, models.recipeParameters(recipe));
        // The body's locals must not collide with the factory-method parameters it copies; the context is always
        // reached through this.context, so a parameter named "context" cannot stand in for it.
        Set<String> parameterNames = factoryMethod.method().getParameters().stream()
                .map(parameter -> parameter.getSimpleName().toString())
                .collect(Collectors.toSet());
        ExecutableElement providesMethod = recipe.providesMethod();
        Optional<CodeBlock> product = scopedProduct(factoryMethod, factoryInterface, models);
        // An unscoped product is reported to the construction listeners here; createScoped reports a scoped one.
        String returned = product.isEmpty() ? "return this.context.constructed($L)" : "return $L";
        CodeBlock.Builder body = CodeBlock.newBuilder();
        if (providesMethod == null) {
            constructorBody(
                    body,
                    recipe,
                    arguments,
                    models.members(recipe.owner()),
                    models,
                    unusedName("instance", parameterNames),
                    returned);
        } else {
            providesBody(body, recipe, providesMethod, arguments, unusedName("host", parameterNames), returned);
        }
        if (product.isEmpty()) {
            return method.addCode(body.build()).build();
        }
        // The host caches the product: its @PreDestroy runs when the host closes.
        String preDestroyName = providesMethod == null ? models.preDestroyName(recipe.owner()) : null;
        String destroyed = unusedName("destroyed", parameterNames);
        CodeBlock preDestroy = preDestroyName == null
                ? CodeBlock.of("null")
                : CodeBlock.of("$N -> $N.$L()", destroyed, destroyed, preDestroyName);
        return method.addStatement(
                        "return this.context.createScoped($L, $L, () -> {\n$>$L$<})",
                        product.get(),
                        preDestroy,
                        body.build())
                .build();
    }

    /** {@return {@code base}, suffixed with underscores until it differs from every name in {@code taken}} */
    private static String unusedName(String base, Set<String> taken) {
        String name = base;
        while (taken.contains(name)) {
            name += "_";
        }
        return name;
    }

    private static void constructorBody(
            CodeBlock.Builder method,
            AssistedRecipe recipe,
            CodeBlock arguments,
            MemberInjection members,
            ModelFactory models,
            String instance,
            String returned) {
        ClassName beanType = ClassName.of(recipe.owner());
        method.addStatement("$T $N = new $T($L)", beanType, instance, beanType, arguments);
        // Mirrors Factory.build(): construct, @Inject methods, then fields (@Inject fields before plugin fields, as
        // injectFields emits them).
        for (MethodModel injected : members.methods()) {
            CodeBlock args = injected.parameters().stream()
                    .map(AssistedFactoryGenerator::resolve)
                    .collect(CodeBlock.joining(", "));
            method.addStatement("$N.$L($L)", instance, injected.name(), args);
        }
        for (FieldModel field : members.fields()) {
            method.addStatement(
                    "$N.$L = $L",
                    instance,
                    field.name(),
                    resolve(field.type(), field.qualifier(), field.isProvider(), null));
        }
        for (PluginField pluginField : members.pluginFields()) {
            method.addStatement("$N.$L = $L", instance, pluginField.name(), pluginField.initializer());
        }
        String postConstruct = models.postConstructName(recipe.owner());
        if (postConstruct != null) {
            method.addStatement("$N.$L()", instance, postConstruct);
        }
        method.addStatement(returned, CodeBlock.of("$N", instance));
    }

    private static void providesBody(
            CodeBlock.Builder method,
            AssistedRecipe recipe,
            ExecutableElement providesMethod,
            CodeBlock arguments,
            String host,
            String returned) {
        String methodName = providesMethod.getSimpleName().toString();
        ClassName hostType = ClassName.of(recipe.owner());
        if (providesMethod.getModifiers().contains(Modifier.STATIC)) {
            method.addStatement(returned, CodeBlock.of("$T.$L($L)", hostType, methodName, arguments));
        } else {
            method.addStatement(
                    "$T $N = this.context.getProvider($T.of($T.class)).get()", hostType, host, KEY, hostType);
            method.addStatement(returned, CodeBlock.of("$N.$L($L)", host, methodName, arguments));
        }
    }

    /**
     * {@return the recipe's call arguments: container parameters resolved through the context, assisted ones passed
     * through by the factory-method parameter name at the matched index}
     */
    private static CodeBlock callArguments(
            AssistedFactoryMethod factoryMethod, Map<Integer, ParameterModel> recipeParameters) {
        List<? extends VariableElement> recipeElements =
                factoryMethod.recipe().executable().getParameters();
        List<? extends VariableElement> factoryParameters =
                factoryMethod.method().getParameters();
        List<Integer> assistedArgumentOrder = factoryMethod.assistedArgumentOrder();
        List<CodeBlock> arguments = new ArrayList<>();
        int assistedIndex = 0;
        for (int index = 0; index < recipeElements.size(); index++) {
            if (MoreElements.isAnnotatedWith(recipeElements.get(index), Assisted.class)) {
                int factoryIndex = assistedArgumentOrder.get(assistedIndex++);
                arguments.add(CodeBlock.of(
                        "$L",
                        factoryParameters.get(factoryIndex).getSimpleName().toString()));
            } else {
                arguments.add(resolve(Objects.requireNonNull(recipeParameters.get(index), "container parameter")));
            }
        }
        return arguments.stream().collect(CodeBlock.joining(", "));
    }

    private static CodeBlock resolve(ParameterModel parameter) {
        return resolve(parameter.type(), parameter.qualifier(), parameter.isProvider(), parameter.pluginInitializer());
    }

    private static CodeBlock resolve(
            CodeBlock type,
            @Nullable RenderedQualifier qualifier,
            boolean isProvider,
            @Nullable CodeBlock pluginInitializer) {
        if (pluginInitializer != null) {
            return pluginInitializer;
        }
        CodeBlock key = qualifier == null
                ? CodeBlock.of("$T.of($L)", KEY, type)
                : CodeBlock.of("$T.of($L, $L)", KEY, type, qualifier.expression());
        CodeBlock provider = CodeBlock.of("this.context.getProvider($L)", key);
        return isProvider ? provider : CodeBlock.of("$L.get()", provider);
    }

    /** The generated impl and factory sources. */
    record Generated(JavaFile implFile, JavaFile factoryFile) {}
}
