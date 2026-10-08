package be.imgn.inject.context;

import org.jspecify.annotations.Nullable;

/** The innermost hop of an interceptor chain: the call to the bean's own implementation. */
@FunctionalInterface
public interface Joinpoint {
    /**
     * Returns the intercepted method's own result.
     *
     * @param arguments the arguments to call it with, positionally
     * @return the intercepted method's own result
     * @throws Throwable anything the method raises
     */
    @Nullable
    Object invoke(@Nullable Object[] arguments) throws Throwable;
}
