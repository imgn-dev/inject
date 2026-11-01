# `Provider<T>` Injection

## The problem

Plain injection hands a consumer one instance, built before the consumer
exists. That is wrong when the consumer needs an instance *later* (deferred,
possibly never), or needs *several* over its lifetime (one per unit of work),
or when eager construction of a heavy dependency would slow down building the
consumer itself.

## How it solves it

Declaring a dependency as `Provider<T>` instead of `T` injects the resolution
handle rather than a resolved value. Each `get()` performs a fresh resolution:

```java
public final class GreetingService {
    private final Provider<Clock> clock;

    @Inject
    public GreetingService(Provider<Clock> clock) {
        this.clock = clock;
    }

    public Clock now() {
        return clock.get(); // resolved here, on every call
    }
}
```

`Provider` is a `@FunctionalInterface` with a single method, `T get()`.

Scope still governs what `get()` returns: a provider of a prototype bean
builds a new instance per call; a provider of a `@Singleton` returns the one
shared instance every time. `Provider` defers *when* resolution happens, the
scope decides *what* it yields.

Providers are also available programmatically without declaring a dependency:

```java
Provider<Registry> registries = injector.getProvider(Registry.class);
```

## Limitations

- `get()` resolves through the registry every call. There is no memoizing
  wrapper that caches the first result at the injection point; caching is the
  bean's scope's job, or the caller's.
- A `Provider` obtained from a live injector never reports absence: if the
  binding is missing, `get()` throws `UnsatisfiedDependencyException` — the
  provider is not a presence probe (see
  [injector injection](injector-injection.md) for probing).
