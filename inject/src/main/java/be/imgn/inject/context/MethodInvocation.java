package be.imgn.inject.context;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One intercepted call, as seen by one interceptor. Every member is a compile-time constant baked into the generated
 * proxy: reading this needs no reflection.
 */
public interface MethodInvocation {

    /**
     * Returns the intercepted instance.
     *
     * @return the intercepted instance
     */
    Object target();

    /**
     * Returns the intercepted method's name.
     *
     * @return the intercepted method's name
     */
    String methodName();

    /**
     * Returns the type declaring the intercepted method.
     *
     * @return the type declaring the intercepted method
     */
    Class<?> declaringType();

    /**
     * Returns the intercepted method's return type; {@code void.class} for a void method.
     *
     * @return the intercepted method's return type; {@code void.class} for a void method
     */
    Class<?> returnType();

    /**
     * Returns the intercepted method's parameter types, in order.
     *
     * @return the intercepted method's parameter types, in order
     */
    List<Class<?>> parameterTypes();

    /**
     * Returns the call arguments, in order; unmodifiable.
     *
     * @return the call arguments, in order; unmodifiable
     */
    List<@Nullable Object> arguments();

    /**
     * Returns the annotation that pulled this interceptor into the chain, with its compile-time member values.
     *
     * @return the annotation that pulled this interceptor into the chain, with its compile-time member values
     */
    AnnotationValue annotation();

    /**
     * Returns the result of the rest of the chain, with the current arguments.
     *
     * @return the result of the rest of the chain, with the current arguments
     * @throws Throwable anything the rest of the chain raises
     */
    @Nullable
    Object proceed() throws Throwable;

    /**
     * Returns the result of the rest of the chain, with {@code arguments} replacing the current ones.
     *
     * @param arguments the substituted arguments, positionally
     * @return the result of the rest of the chain, with {@code arguments} replacing the current ones
     * @throws IllegalArgumentException if the count or a type does not match the intercepted method
     * @throws Throwable anything the rest of the chain raises
     */
    @Nullable
    Object proceed(@Nullable Object... arguments) throws Throwable;
}
