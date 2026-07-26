package be.imgn.inject.examples;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Inject;
import be.imgn.inject.Intercepts;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.MethodInterceptor;
import be.imgn.inject.context.MethodInvocation;

/** Handles {@link Timed}: logs entry and exit, and strips a leading/trailing-blank first argument. */
@Singleton
@Intercepts(Timed.class)
public class TimingInterceptor implements MethodInterceptor {

    private final CallLog log;

    /** @param log the shared call log */
    @Inject
    public TimingInterceptor(CallLog log) {
        this.log = log;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        String tag = invocation.annotation().stringValue("value").orElse(invocation.methodName());
        log.add("timed:enter:" + tag);
        try {
            if (invocation.arguments().size() == 1 && invocation.arguments().get(0) instanceof String argument) {
                return invocation.proceed(argument.strip());
            }
            return invocation.proceed();
        } finally {
            log.add("timed:exit:" + tag);
        }
    }
}
