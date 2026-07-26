package be.imgn.inject.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import be.imgn.inject.Provider;

/**
 * An intercepted method's immutable call-site description: what the method is, and which annotations bind interceptors
 * to it, outermost first. Generated proxies hold one static instance per intercepted method and {@link #bind} it once
 * per proxy instance.
 */
public final class InterceptedMethod {

    private final Class<?> declaringType;
    private final String methodName;
    private final Class<?> returnType;
    private final List<Class<?>> parameterTypes;
    private final List<AnnotationValue> bindings;

    private InterceptedMethod(
            Class<?> declaringType,
            String methodName,
            Class<?> returnType,
            List<Class<?>> parameterTypes,
            List<AnnotationValue> bindings) {
        this.declaringType = declaringType;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.bindings = bindings;
    }

    /**
     * {@return a call-site description}
     *
     * @param declaringType the type declaring the intercepted method
     * @param methodName the method name
     * @param returnType the return type; {@code void.class} for a void method
     * @param parameterTypes the parameter types, in order
     * @param bindings the intercepting annotations, outermost first; must not be empty
     */
    public static InterceptedMethod of(
            Class<?> declaringType,
            String methodName,
            Class<?> returnType,
            List<Class<?>> parameterTypes,
            List<AnnotationValue> bindings) {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(returnType, "returnType");
        List<Class<?>> parameters = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
        List<AnnotationValue> copied = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("An intercepted method needs at least one binding: " + methodName);
        }
        return new InterceptedMethod(declaringType, methodName, returnType, parameters, copied);
    }

    /** {@return the type declaring the intercepted method} */
    public Class<?> declaringType() {
        return declaringType;
    }

    /** {@return the method name} */
    public String methodName() {
        return methodName;
    }

    /** {@return the return type} */
    public Class<?> returnType() {
        return returnType;
    }

    /** {@return the parameter types, in order} */
    public List<Class<?>> parameterTypes() {
        return parameterTypes;
    }

    /** {@return the intercepting annotations, outermost first} */
    public List<AnnotationValue> bindings() {
        return bindings;
    }

    /**
     * {@return this call site bound to {@code context}: one interceptor provider per binding, in order}
     *
     * @param context the resolution context
     * @throws be.imgn.inject.InterceptorResolutionException if a binding has no interceptor bean
     */
    public MethodInterception bind(Context context) {
        Objects.requireNonNull(context, "context");
        List<Provider<MethodInterceptor>> providers = new ArrayList<>(bindings.size());
        for (AnnotationValue binding : bindings) {
            providers.add(context.interceptor(binding.annotationType()));
        }
        return new MethodInterception(this, List.copyOf(providers));
    }

    @Override
    public String toString() {
        return declaringType.getName() + "." + methodName;
    }
}
