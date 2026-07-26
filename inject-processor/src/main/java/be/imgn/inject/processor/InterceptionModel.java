package be.imgn.inject.processor;

import java.util.List;

/**
 * The data needed to generate one bean's intercepted proxy.
 *
 * @param proxySimpleName the generated proxy's simple name
 * @param constructorParameters the bean's {@code @Inject} constructor parameters, mirrored on the proxy constructor
 * @param methods the intercepted methods, in declaration order
 */
record InterceptionModel(
        String proxySimpleName, List<ProxyParameter> constructorParameters, List<InterceptedMethodModel> methods) {}
