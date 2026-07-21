package it;

import java.lang.annotation.Annotation;

import be.imgn.inject.Provider;
import be.imgn.inject.context.Scope;

/** A custom scope, registered via {@code META-INF/services/be.imgn.inject.context.Scope}. */
public final class ThreadScope implements Scope {
    @Override
    public Class<? extends Annotation> annotationType() {
        return ThreadScoped.class;
    }

    @Override
    public <T> Provider<T> wrap(Provider<T> provider) {
        return provider;
    }
}
