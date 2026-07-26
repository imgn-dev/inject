package be.imgn.inject.examples.modular;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Inject;
import be.imgn.inject.Intercepts;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.context.MethodInvocation;

/** Handles {@link Traced} by prefixing the result. An interceptor needs no {@code provides} directive of its own. */
@Singleton
@Intercepts(Traced.class)
public class TracingInterceptor implements MethodInterceptor {

    /** Creates the interceptor. */
    @Inject
    public TracingInterceptor() {}

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        return "traced:" + invocation.proceed();
    }
}
