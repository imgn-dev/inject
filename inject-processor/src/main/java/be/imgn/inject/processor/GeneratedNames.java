package be.imgn.inject.processor;

import java.util.List;

/**
 * Builds the simple names of generated types. All generated types live in the same package as the type they are
 * generated for; the simple name is {@code $} + the enclosing-class chain (outermost &rarr; target) joined by {@code $}
 * + a {@code $$<Kind>} suffix.
 */
final class GeneratedNames {

    private GeneratedNames() {}

    /**
     * {@return the factory name for a bean whose enclosing-class chain (outermost first) is {@code enclosingChain}}
     *
     * @param enclosingChain the simple names from the outermost enclosing class down to the bean
     */
    static String factoryName(List<String> enclosingChain) {
        return "$" + String.join("$", enclosingChain) + "$$Factory";
    }

    /**
     * {@return the factory name for a top-level bean}
     *
     * @param simpleName the bean's simple name
     */
    static String factoryName(String simpleName) {
        return factoryName(List.of(simpleName));
    }

    /**
     * {@return the impl name for an {@code @AssistedFactory} interface whose enclosing-class chain (outermost first) is
     * {@code enclosingChain}}
     *
     * @param enclosingChain the simple names from the outermost enclosing class down to the interface
     */
    static String implName(List<String> enclosingChain) {
        return "$" + String.join("$", enclosingChain) + "$$Impl";
    }

    /**
     * {@return the impl name for a top-level {@code @AssistedFactory} interface}
     *
     * @param simpleName the interface's simple name
     */
    static String implName(String simpleName) {
        return implName(List.of(simpleName));
    }

    /**
     * {@return the scope-binding name for a scoping annotation whose enclosing-class chain is {@code enclosingChain}}
     *
     * @param enclosingChain the simple names from the outermost enclosing class down to the annotation
     */
    static String scopeName(List<String> enclosingChain) {
        return "$" + String.join("$", enclosingChain) + "$$Scope";
    }

    /**
     * {@return the scope-binding name for a top-level scoping annotation}
     *
     * @param simpleName the annotation's simple name
     */
    static String scopeName(String simpleName) {
        return scopeName(List.of(simpleName));
    }

    /**
     * {@return the factory name for a {@code @Provides} method}
     *
     * @param hostChain the host type's enclosing-class chain, outermost first
     * @param methodName the {@code @Provides} method name
     * @param index disambiguation index for overloaded methods ({@code 0} = no suffix)
     */
    static String providesFactoryName(List<String> hostChain, String methodName, int index) {
        String base = "$" + String.join("$", hostChain) + "$" + methodName;
        return index == 0 ? base + "$$Factory" : base + "$" + index + "$$Factory";
    }

    /**
     * {@return the factory name for a {@code @Provides} method on a top-level host}
     *
     * @param host the host type's simple name
     * @param methodName the method name
     * @param index disambiguation index for overloaded methods ({@code 0} = no suffix)
     */
    static String providesFactoryName(String host, String methodName, int index) {
        return providesFactoryName(List.of(host), methodName, index);
    }

    /**
     * {@return the intercepted-proxy name for a bean whose enclosing-class chain (outermost first) is
     * {@code enclosingChain}}
     *
     * @param enclosingChain the simple names from the outermost enclosing class down to the bean
     */
    static String interceptedName(List<String> enclosingChain) {
        return "$" + String.join("$", enclosingChain) + "$$Intercepted";
    }

    /**
     * {@return the intercepted-proxy name for a top-level bean}
     *
     * @param simpleName the bean's simple name
     */
    static String interceptedName(String simpleName) {
        return interceptedName(List.of(simpleName));
    }
}
