package be.imgn.inject.context;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.Provider;

/**
 * Wraps a provider so its delegate is invoked exactly once; every {@link #get()} returns the same instance. Thread-safe
 * via double-checked locking.
 *
 * @param <T> the provided type
 */
public final class SingletonProvider<T> implements Provider<T> {
    private final Provider<T> delegate;
    private volatile @Nullable T instance;

    /** @param delegate the provider to memoize */
    public SingletonProvider(Provider<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public T get() {
        T local = instance;
        if (local == null) {
            synchronized (this) {
                local = instance;
                if (local == null) {
                    local = Objects.requireNonNull(delegate.get(), "delegate.get()");
                    instance = local;
                }
            }
        }
        return local;
    }
}
