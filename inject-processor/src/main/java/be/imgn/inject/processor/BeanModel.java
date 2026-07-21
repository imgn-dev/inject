package be.imgn.inject.processor;

import java.util.List;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * The data needed to generate one bean factory.
 *
 * @param packageName the package the factory is emitted into (the bean's package)
 * @param factorySimpleName the generated factory's simple name
 * @param beanType the bean type the factory builds
 * @param constructorParameters the injectable constructor's parameters, in order (empty for a no-arg constructor)
 * @param constructorMetadata an {@code AnnotationMetadata} expression for the constructor's framework-related
 *     annotations, including those on the bean type
 * @param definitionMetadata an {@code AnnotationMetadata} expression for the framework-related annotations declared on
 *     the bean type itself, exposed through {@code BeanDefinition}
 * @param fields the context-resolved {@code @Inject} fields, in order
 * @param methods the {@code @Inject} methods, in order
 * @param pluginFields the plugin-initialized fields (via an {@code @InjectingAnnotation}), in order
 * @param scopeAnnotation the bean's scoping annotation type, or {@code null} for prototype scope
 * @param beanQualifier the rendered qualifier applied to every exposed key, or {@code null}
 * @param exposedTypes the types the bean is exposed as; empty means "use the factory's default single key"
 */
record BeanModel(
        String packageName,
        String factorySimpleName,
        ClassName beanType,
        List<ParameterModel> constructorParameters,
        CodeBlock constructorMetadata,
        CodeBlock definitionMetadata,
        List<FieldModel> fields,
        List<MethodModel> methods,
        List<PluginField> pluginFields,
        @Nullable ClassName scopeAnnotation,
        @Nullable RenderedQualifier beanQualifier,
        List<TypeName> exposedTypes) {}
