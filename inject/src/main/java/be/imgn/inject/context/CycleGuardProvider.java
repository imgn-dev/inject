package be.imgn.inject.context;

import java.util.Objects;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

/** Detects re-entrant resolution of the same key and reports the cycle path. */
final class CycleGuardProvider<T> implements Provider<T> {
    private final Key<?> key;
    private final Provider<T> delegate;
    private final ResolutionStack stack;

    CycleGuardProvider(Key<?> key, Provider<T> delegate, ResolutionStack stack) {
        this.key = Objects.requireNonNull(key, "key");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.stack = Objects.requireNonNull(stack, "stack");
    }

    @Override
    public T get() {
        stack.enter(key);
        try {
            return delegate.get();
        } finally {
            stack.exit();
        }
    }
}
