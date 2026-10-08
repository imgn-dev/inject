# Scopes

## The problem

Some objects are cheap and stateless — a fresh one per use is correct and
avoids shared-state bugs. Others are expensive or must be shared — a
connection pool, a registry. One instantiation policy cannot fit both, and
hand-managing "the shared ones" reintroduces the global variables injection
was meant to remove.

## How it solves it

The default is **prototype**: every resolution builds a new instance. Sharing
is opt-in, declared where the bean is declared.

`@Singleton` is built in — one instance per injector, created on first use,
exactly once even under concurrent resolution:

```java
@Singleton
public final class Registry {
    @Inject
    public Registry() {}
}
```

```java
Injector injector = Injector.create();
assert injector.getInstance(Registry.class) == injector.getInstance(Registry.class);
```

`@Provides` methods scope the same way — put `@Singleton` on the method.

### Custom scopes

A scope is a pair: an annotation meta-annotated with `@ScopingAnnotation`, and
a `Scope` implementation that wraps the bean's provider with a caching policy.
Implementations are discovered through `ServiceLoader`.

```java
@ScopingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ThreadScoped {}

public final class ThreadScope implements Scope {
    @Override
    public Class<? extends Annotation> annotationType() {
        return ThreadScoped.class;
    }

    @Override
    public <T> Provider<T> wrap(Provider<T> provider) {
        return new ThreadScopedProvider<>(provider); // caches per thread
    }
}
```

```java
@ThreadScoped
public final class RequestScopedToken {
    @Inject
    public RequestScopedToken() {}
}
```

The wrap-a-provider contract keeps custom scopes small: the framework hands the
scope the raw building provider once, at registry construction, and every
resolution goes through the returned wrapper.

## Limitations

- Scopes control instantiation. Releasing scoped instances is a separate
  concern, handled by [lifecycle callbacks](lifecycle-callbacks.md): a
  `@PreDestroy` method runs when the owning injector closes.
- A bean carries at most one scoping annotation.
- A scoping annotation has exactly one `Scope`: two discovered implementations
  for the same annotation are an error at injector creation
  (`ScopeResolutionException`), so a jar cannot silently replace a scope,
  built-in `@Singleton` included.
- Scopes are declared on the bean, not chosen at the injection point; a
  consumer cannot request "a fresh instance" of a singleton (though injecting a
  [`Provider`](provider-injection.md) of a prototype bean gives fresh instances
  on demand).
- [Child injectors](child-injectors.md) add no scopes of their own: a scoped
  bean anchors to the injector whose registry declared it.
