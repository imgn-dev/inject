package be.imgn.inject.processor;

import java.util.List;

import com.palantir.javapoet.ClassName;

/**
 * The data needed to generate one bean factory.
 *
 * @param packageName the package the factory is emitted into (the bean's package)
 * @param factorySimpleName the generated factory's simple name
 * @param beanType the bean type the factory builds
 * @param constructorParameters the injectable constructor's parameters, in order (empty for a no-arg constructor)
 * @param fields the {@code @Inject} fields, in order
 * @param methods the {@code @Inject} methods, in order
 */
record BeanModel(
        String packageName,
        String factorySimpleName,
        ClassName beanType,
        List<ParameterModel> constructorParameters,
        List<FieldModel> fields,
        List<MethodModel> methods) {}
