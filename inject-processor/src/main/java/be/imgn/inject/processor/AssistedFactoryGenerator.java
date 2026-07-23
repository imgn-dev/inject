package be.imgn.inject.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Assisted;

/**
 * Emits an {@code @AssistedFactory} interface's implementation and its {@code Factory} registration: an impl class that
 * resolves container parameters through the {@code Context} and passes assisted parameters through verbatim, plus a
 * {@code Factory} subclass that constructs it.
 */
final class AssistedFactoryGenerator {

    private static final ClassName FACTORY = ClassName.get("be.imgn.inject.context", "Factory");
    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName ANNOTATION_METADATA = ClassName.get("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName KEY = ClassName.get("be.imgn.inject", "Key");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");

    private AssistedFactoryGenerator() {}

    /**
     * The generated impl and factory sources, plus the factory's qualified name for {@code ServiceLoader} registration.
     */
    record Generated(JavaFile implFile, JavaFile factoryFile, String factoryQualifiedName) {}

    static Generated generate(AssistedFactoryModel model, ModelFactory models, Elements elements) {
        TypeElement factoryInterface = model.factoryInterface();
        String packageName =
                elements.getPackageOf(factoryInterface).getQualifiedName().toString();
        ClassName interfaceType = ClassName.get(factoryInterface);
        List<String> chain = ModelFactory.enclosingChain(factoryInterface);
        ClassName implType = ClassName.get(packageName, GeneratedNames.implName(chain));
        String factorySimpleName = GeneratedNames.factoryName(chain);

        JavaFile implFile = JavaFile.builder(packageName, implType(model, models, interfaceType, implType))
                .build();
        JavaFile factoryFile = JavaFile.builder(
                        packageName,
                        factoryType(factorySimpleName, interfaceType, implType, factoryInterface, elements))
                .build();
        return new Generated(implFile, factoryFile, packageName + "." + factorySimpleName);
    }

    private static TypeSpec implType(
            AssistedFactoryModel model, ModelFactory models, ClassName interfaceType, ClassName implType) {
        TypeSpec.Builder type = TypeSpec.classBuilder(implType)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(interfaceType)
                .addField(CONTEXT, "context", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(CONTEXT, "context")
                        .addStatement("this.context = $T.requireNonNull(context, $S)", OBJECTS, "context")
                        .build());
        for (AssistedFactoryMethod method : model.methods()) {
            type.addMethod(createMethod(method, models));
        }
        return type.build();
    }

    private static TypeSpec factoryType(
            String factorySimpleName,
            ClassName interfaceType,
            ClassName implType,
            TypeElement factoryInterface,
            Elements elements) {
        return TypeSpec.classBuilder(factorySimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(FACTORY, interfaceType))
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("super($T.class)", interfaceType)
                        .build())
                .addMethod(MethodSpec.methodBuilder("createInstance")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PROTECTED)
                        .returns(interfaceType)
                        .addParameter(CONTEXT, "context")
                        .addStatement("return new $T(context)", implType)
                        .build())
                .addMethod(MethodSpec.methodBuilder("annotationMetadata")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ANNOTATION_METADATA)
                        .addStatement("return $L", Annotations.metadata(List.of(factoryInterface), elements))
                        .build())
                .build();
    }

    private static MethodSpec createMethod(AssistedFactoryMethod factoryMethod, ModelFactory models) {
        MethodSpec.Builder method = MethodSpec.overriding(factoryMethod.method());
        AssistedRecipe recipe = factoryMethod.recipe();
        CodeBlock arguments = callArguments(factoryMethod, models.recipeParameters(recipe));
        ExecutableElement providesMethod = recipe.providesMethod();
        if (providesMethod == null) {
            constructorBody(method, recipe, arguments, models.members(recipe.owner()));
        } else {
            providesBody(method, recipe, providesMethod, arguments);
        }
        return method.build();
    }

    private static void constructorBody(
            MethodSpec.Builder method, AssistedRecipe recipe, CodeBlock arguments, MemberInjection members) {
        ClassName beanType = ClassName.get(recipe.owner());
        method.addStatement("$T instance = new $T($L)", beanType, beanType, arguments);
        // Mirrors Factory.build(): construct, @Inject methods, then fields (@Inject fields before plugin fields, as
        // injectFields emits them).
        for (MethodModel injected : members.methods()) {
            CodeBlock args = injected.parameters().stream()
                    .map(AssistedFactoryGenerator::resolve)
                    .collect(CodeBlock.joining(", "));
            method.addStatement("instance.$L($L)", injected.name(), args);
        }
        for (FieldModel field : members.fields()) {
            method.addStatement(
                    "instance.$L = $L",
                    field.name(),
                    resolve(field.type(), field.qualifier(), field.isProvider(), null));
        }
        for (PluginField pluginField : members.pluginFields()) {
            method.addStatement("instance.$L = $L", pluginField.name(), pluginField.initializer());
        }
        method.addStatement("return instance");
    }

    private static void providesBody(
            MethodSpec.Builder method, AssistedRecipe recipe, ExecutableElement providesMethod, CodeBlock arguments) {
        String methodName = providesMethod.getSimpleName().toString();
        ClassName hostType = ClassName.get(recipe.owner());
        if (providesMethod.getModifiers().contains(Modifier.STATIC)) {
            method.addStatement("return $T.$L($L)", hostType, methodName, arguments);
        } else {
            method.addStatement("$T host = context.getProvider($T.of($T.class)).get()", hostType, KEY, hostType);
            method.addStatement("return host.$L($L)", methodName, arguments);
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
            if (recipeElements.get(index).getAnnotation(Assisted.class) != null) {
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
        CodeBlock provider = CodeBlock.of("context.getProvider($L)", key);
        return isProvider ? provider : CodeBlock.of("$L.get()", provider);
    }
}
