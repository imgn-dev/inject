package be.imgn.inject.examples;

import be.imgn.inject.Provider;
import be.imgn.inject.context.Scope;
import java.lang.annotation.Annotation;

/**
 * Handles the {@link ThreadScoped} scope by memoizing the wrapped provider per thread, so each thread sees its own
 * instance. Discovered via {@code ServiceLoader} (see {@code META-INF/services/be.imgn.inject.context.Scope}).
 */
public final class ThreadScope implements Scope {
    @Override
    public Class<? extends Annotation> annotationType() {
        return ThreadScoped.class;
    }

    @Override
    public <T> Provider<T> wrap(Provider<T> provider) {
        return new ThreadScopedProvider<>(provider);
    }
}
