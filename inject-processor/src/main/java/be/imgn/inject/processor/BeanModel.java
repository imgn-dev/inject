package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.gaia.processing.source.TypeName;
import java.util.List;
import javax.lang.model.element.TypeElement;
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
 * @param supertypeTypes the types among {@code exposedTypes} the bean has only through its supertypes: all but its own
 *     class, unless {@code @ExposeAs} chose them or the bean is qualified
 * @param contribution the collection contribution, or {@code null} when the bean binds under its own keys
 * @param postConstruct the bean's {@code @PostConstruct} method name, or {@code null}
 * @param preDestroy the bean's {@code @PreDestroy} method name, or {@code null}
 * @param interceptsAnnotation the annotation this bean intercepts (from {@code @Intercepts}), or {@code null}
 * @param interception the intercepted-proxy model, or {@code null} when the bean has no intercepted method
 * @param origin the bean type, which the factory is generated from
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
        List<TypeName> exposedTypes,
        List<TypeName> supertypeTypes,
        @Nullable ContributionModel contribution,
        @Nullable String postConstruct,
        @Nullable String preDestroy,
        @Nullable ClassName interceptsAnnotation,
        @Nullable InterceptionModel interception,
        TypeElement origin) {}
