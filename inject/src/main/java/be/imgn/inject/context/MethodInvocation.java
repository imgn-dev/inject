package be.imgn.inject.context;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One intercepted call, as seen by one interceptor. Every member is a compile-time constant baked into the generated
 * proxy: reading this needs no reflection.
 */
public interface MethodInvocation {

    /** {@return the intercepted instance} */
    Object target();

    /** {@return the intercepted method's name} */
    String methodName();

    /** {@return the type declaring the intercepted method} */
    Class<?> declaringType();

    /** {@return the intercepted method's return type; {@code void.class} for a void method} */
    Class<?> returnType();

    /** {@return the intercepted method's parameter types, in order} */
    List<Class<?>> parameterTypes();

    /** {@return the call arguments, in order; unmodifiable} */
    List<@Nullable Object> arguments();

    /** {@return the annotation that pulled this interceptor into the chain, with its compile-time member values} */
    AnnotationValue annotation();

    /**
     * {@return the result of the rest of the chain, with the current arguments}
     *
     * @throws Throwable anything the rest of the chain raises
     */
    @Nullable
    Object proceed() throws Throwable;

    /**
     * {@return the result of the rest of the chain, with {@code arguments} replacing the current ones}
     *
     * @param arguments the substituted arguments, positionally
     * @throws IllegalArgumentException if the count or a type does not match the intercepted method
     * @throws Throwable anything the rest of the chain raises
     */
    @Nullable
    Object proceed(@Nullable Object... arguments) throws Throwable;
}
