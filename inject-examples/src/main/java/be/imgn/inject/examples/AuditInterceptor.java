package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.Intercepts;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.context.MethodInvocation;
import org.jspecify.annotations.Nullable;

/** Handles {@link Audited}: records the call at the outermost position of the chain. */
@Singleton
@Intercepts(Audited.class)
public class AuditInterceptor implements MethodInterceptor {

    private final CallLog log;

    /** @param log the shared call log */
    @Inject
    public AuditInterceptor(CallLog log) {
        this.log = log;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        log.add("audit:enter:" + invocation.methodName());
        try {
            return invocation.proceed();
        } finally {
            log.add("audit:exit:" + invocation.methodName());
        }
    }
}
