package be.imgn.inject.processor;

import java.util.List;

import com.palantir.javapoet.ClassName;

/**
 * The data needed to generate one bean factory.
 *
 * @param packageName the package the factory is emitted into (the bean's package)
 * @param factorySimpleName the generated factory's simple name
 * @param beanType the bean type the factory builds
 * @param constructorParameters the injectable constructor's parameters, in order
 */
record BeanModel(
        String packageName, String factorySimpleName, ClassName beanType, List<ParameterModel> constructorParameters) {}
