# Type Exposure and `@ExposeAs`

## The problem

A bean has one implementation type but is usually consumed through an
interface. Consumers should depend on `Engine`, not `V8Engine` — and a codebase
that *can* inject `V8Engine` eventually will, welding callers to the
implementation.

## How it solves it

By default a bean is injectable under **all of its types**: its own class and
every supertype and interface it implements, all resolving to the same
binding (and, if scoped, the same instance):

```java
@Singleton
public final class Registry implements Cataloged {
    @Inject
    public Registry() {}
}
```

```java
// both resolve to the same singleton
Registry byClass = injector.getInstance(Registry.class);
Cataloged byInterface = injector.getInstance(Cataloged.class);
```

Universal contracts are excluded from that default so they never become
accidental binding keys: `Object`, `Serializable`, `Cloneable`, `Comparable`,
`Runnable`, `AutoCloseable`, `Closeable`, `Iterable`, `EventListener`,
`MethodInterceptor`, and the framework's own types.

`@ExposeAs` replaces the default with an explicit allowlist. The bean is then
injectable **only** as the listed types — its concrete class included only if
listed:

```java
@Singleton
@ExposeAs(Engine.class)
public final class V8Engine implements Engine {
    @Inject
    public V8Engine() {}
}
```

```java
injector.getInstance(Engine.class);   // works, returns the V8Engine singleton
injector.getInstance(V8Engine.class); // UnsatisfiedDependencyException
```

That turns "depend on the interface" from a convention into a rule the
injector enforces: the implementation type is not a key at all.

## Limitations

- Default exposure means two beans implementing the same interface collide on
  that interface's key (`DuplicateBindingException`) even if nothing injects
  it. Disambiguate with [qualifiers](qualifiers.md), `@ExposeAs` allowlists, or
  a [collection binding](multibindings.md).
- The excluded-contracts list is fixed; the only way to change what a bean
  exposes is `@ExposeAs`.
- A [collection contribution](multibindings.md) (`@IntoSet`/`@IntoMap`) cannot
  also use `@ExposeAs` — a contribution is reachable through its collection
  only.
