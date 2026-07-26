package be.imgn.inject.context;

import org.jspecify.annotations.Nullable;

/**
 * Runs around an intercepted method call. Implementations are ordinary beans annotated
 * {@link be.imgn.inject.Intercepts}, so they are injected, scoped, and conditioned like any other bean.
 */
public interface MethodInterceptor {
    /**
     * {@return the call's result, which may be the value {@link MethodInvocation#proceed()} returned or one of this
     * interceptor's own}
     *
     * @param invocation the call being intercepted
     * @throws Throwable anything the rest of the chain or this interceptor raises
     */
    @Nullable
    Object invoke(MethodInvocation invocation) throws Throwable;
}
