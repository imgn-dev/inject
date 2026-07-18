package be.imgn.inject.context;

import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.StringJoiner;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.UnsatisfiedDependencyException;

/**
 * Default {@link Context}: loads all {@code Factory} and {@code Scope} services, wraps each factory provider with a
 * cycle guard and its declared scope, and resolves keys lazily. The registry is immutable after construction;
 * resolution is thread-safe.
 */
final class DefaultContext implements Context {
    private final Map<Key<?>, Provider<?>> registry;
    private final ThreadLocal<Deque<Key<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    DefaultContext() {
        this(loadFactories(), loadScopes());
    }

    DefaultContext(Iterable<Factory<?>> factories, Iterable<Scope> scopes) {
        Map<Class<? extends Annotation>, Scope> scopesByAnnotation = new HashMap<>();
        for (Scope scope : scopes) {
            scopesByAnnotation.put(scope.annotationType(), scope);
        }

        Map<Key<?>, Provider<?>> built = new HashMap<>();
        for (Factory<?> factory : factories) {
            Provider<?> provider = wrap(factory, scopesByAnnotation);
            for (Key<?> key : factory.exposedKeys()) {
                if (built.putIfAbsent(key, provider) != null) {
                    throw new DuplicateBindingException("Duplicate binding for " + key);
                }
            }
        }
        this.registry = Map.copyOf(built);
    }

    private static List<Factory<?>> loadFactories() {
        List<Factory<?>> factories = new ArrayList<>();
        for (Factory<?> factory : ServiceLoader.load(Factory.class)) {
            factories.add(factory);
        }
        return factories;
    }

    private static List<Scope> loadScopes() {
        List<Scope> scopes = new ArrayList<>();
        for (Scope scope : ServiceLoader.load(Scope.class)) {
            scopes.add(scope);
        }
        return scopes;
    }

    private <T> Provider<T> wrap(Factory<T> factory, Map<Class<? extends Annotation>, Scope> scopes) {
        Key<T> primary = Key.of(factory.type());
        Provider<T> guarded = new CycleGuardProvider<>(primary, new FactoryProvider<>(this, factory), this);
        Optional<Class<? extends Annotation>> scopeAnnotation = factory.scope();
        if (scopeAnnotation.isEmpty()) {
            return guarded;
        }
        Scope scope = scopes.get(scopeAnnotation.get());
        if (scope == null) {
            throw new ScopeResolutionException(
                    "No scope registered for @" + scopeAnnotation.get().getSimpleName());
        }
        return scope.wrap(guarded);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> getProvider(Key<T> key) {
        Objects.requireNonNull(key, "key");
        Provider<?> provider = registry.get(key);
        if (provider == null) {
            throw new UnsatisfiedDependencyException("No binding for " + key);
        }
        return (Provider<T>) provider;
    }

    void enter(Key<?> key) {
        Deque<Key<?>> current = stack.get();
        if (current.contains(key)) {
            StringJoiner path = new StringJoiner(" -> ");
            current.descendingIterator().forEachRemaining(entered -> path.add(entered.toString()));
            path.add(key.toString());
            throw new CircularDependencyException("Dependency cycle: " + path);
        }
        current.push(key);
    }

    void exit(Key<?> key) {
        stack.get().pop();
    }
}
