package be.imgn.inject.context;

import java.lang.annotation.Annotation;

import be.imgn.inject.Provider;
import be.imgn.inject.Singleton;

/** The built-in {@link Singleton} scope: memoizes the wrapped provider. */
public final class SingletonScope implements Scope {
    @Override
    public Class<? extends Annotation> annotationType() {
        return Singleton.class;
    }

    @Override
    public <T> Provider<T> wrap(Provider<T> provider) {
        return new SingletonProvider<>(provider);
    }
}
