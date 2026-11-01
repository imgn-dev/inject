# Eager singletons

## The problem

A singleton is built the first time something resolves it. Some singletons are
never resolved by anyone, yet must exist: an event subscriber that only
listens, a registrar that wires itself into a framework, a service that starts
a background task from `@PostConstruct`. Left lazy, they are never built, and
resolving each of them by hand at startup spreads a list of classes across the
application's entry point.

## How it solves it

`@Eager` on a `@Singleton` bean, or on a `@Singleton` `@Provides` method, builds
it when the root injector is created:

```java
@Eager
@Singleton
public final class AuditSubscriber {
    @Inject
    public AuditSubscriber(AuditLog log) {
        ...
    }
}
```

```java
public final class Telemetry {
    @Provides
    @Eager
    @Singleton
    static Exporter exporter(TelemetryConfig config) {
        ...
    }
}
```

`@Eager` makes a concrete class a bean on its own: like any bean, it is built
through its `@Inject` constructor, or else its accessible no-argument
constructor. `@Singleton` may be declared on the class or inherited from a
supertype.

`Injector.create()` and `Injector.builder().build()` build the eager singletons
before returning the injector:

1. The registry is built and every bean's dependencies are checked.
2. The [construction listeners](construction-listeners.md) are resolved.
3. Each eager singleton is built, after its dependencies: constructor, member
   injection, `@PostConstruct`. The listeners see it and its dependencies.

A later resolution returns the same instance. An eager singleton can already
use an injected `Injector` or `Provider`: the injector is attached before step 2.

Only beans still in the graph are built: a bean whose
[condition](conditional-beans.md) fails, a `@Default`
binding that lost its key, and a bean every key of which a
[builder override](builder-overrides.md) shadows are not. A bean contributing
to a [multibinding](multibindings.md) is built.

If building an eager singleton, or a listener, fails, the injector is not
created: the singletons already built are destroyed (their `@PreDestroy` runs,
newest first) and the exception propagates.

The processor rejects `@Eager` where it cannot apply: on a bean or `@Provides`
method that is not `@Singleton`, on a method without `@Provides`, on an
interface, abstract class or enum, and on an
[assisted](assisted-injection.md) product or recipe.

## Limitations

- Only `@Singleton` beans can be eager. A child-local bean is not built when a
  child hosting its scope is created, and a custom scope's lifetime is unknown
  to the container.
- Eager singletons are built in no specified order beyond their dependencies:
  one that must exist before another should depend on it.
- They are built on the thread that creates the injector. A singleton that must
  be built on a particular thread, such as a UI toolkit's event thread, needs
  the injector created on that thread.
- An eager singleton that a listener depends on is built while the listeners
  are resolved, before any of them exists, so it is not reported.
- Eagerness cannot be turned off for one injector, for example in a test:
  override the bean's keys with the builder instead.
