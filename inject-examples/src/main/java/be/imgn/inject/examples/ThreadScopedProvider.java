package be.imgn.inject.examples;

import be.imgn.inject.Provider;

/** Memoizes the delegate per thread: the first {@code get()} on a thread builds the instance; later calls reuse it. */
final class ThreadScopedProvider<T> implements Provider<T> {
    private final Provider<T> delegate;

    // One memo per scoped binding, so the ThreadLocal belongs to this instance.
    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<T> perThread = new ThreadLocal<>();

    ThreadScopedProvider(Provider<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T get() {
        T existing = perThread.get();
        if (existing != null) {
            return existing;
        }
        T created = delegate.get();
        perThread.set(created);
        return created;
    }
}
