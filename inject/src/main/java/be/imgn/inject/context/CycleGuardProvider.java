package be.imgn.inject.context;

import java.util.Objects;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

/** Detects re-entrant resolution of the same key and reports the cycle path. */
final class CycleGuardProvider<T> implements Provider<T> {
    private final Key<?> key;
    private final Provider<T> delegate;
    private final DefaultContext context;

    CycleGuardProvider(Key<?> key, Provider<T> delegate, DefaultContext context) {
        this.key = Objects.requireNonNull(key, "key");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public T get() {
        context.enter(key);
        try {
            return delegate.get();
        } finally {
            context.exit(key);
        }
    }
}
