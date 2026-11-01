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

JDK types (`java.*` and `javax.*`) are exposed only as interfaces your own
code names. A JDK interface stays exposed when the bean, or one of its own
supertypes, implements or extends it directly. A JDK superclass is left out,
and so is every type a JDK type brings in:

```java
public final class Scene extends JPanel implements View { ... }
// Scene and View: not JPanel, JComponent, Container, Accessible, ...

public final class Pool implements DataSource { ... }
// Pool and DataSource: not CommonDataSource or Wrapper
```

`@ExposeAs` can still list any JDK type.

Several beans may share a supertype: two views implementing `View` are both
valid beans. A key a bean has only through its supertypes binds it when no
other binding claims that key. When several beans have it this way, it is left
unbound rather than being an error:

```java
injector.getInstance(Editor.class);  // works
injector.getInstance(View.class);    // UnsatisfiedDependencyException:
// No unique binding for Key[View]: implemented by Key[Editor], Key[Preview]. ...
```

Injecting such a key is an error when the injector is created, naming the
injection point and every bean that has the key. A bean's own class, the types
`@ExposeAs` lists, and any other explicit binding always win over a key a bean
has through its supertypes; two explicit bindings of one key are still a
`DuplicateBindingException`. A [qualifier](qualifiers.md) makes all of a bean's
keys explicit: two beans with the same qualifier on a shared interface are a
duplicate.

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

- An interface several beans implement cannot be injected until one of them
  is chosen: with [qualifiers](qualifiers.md), an `@ExposeAs` allowlist, or a
  [collection binding](multibindings.md) for all of them.
- The excluded-contracts list is fixed; the only way to change what a bean
  exposes is `@ExposeAs`.
- A [collection contribution](multibindings.md) (`@IntoSet`/`@IntoMap`) cannot
  also use `@ExposeAs` — a contribution is reachable through its collection
  only.
