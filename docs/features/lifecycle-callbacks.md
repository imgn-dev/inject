# Lifecycle callbacks

## The problem

Construction is not always the whole of setup. A bean may need a step that runs
only once every dependency is in place — open a connection, warm a cache,
register a listener — which a constructor cannot do while fields are still being
injected. And some beans hold resources that must be released: a pool to drain,
a file to flush, a thread to stop. Leaving that to the caller reintroduces the
manual bookkeeping the container was meant to remove.

## How it solves it

Two method annotations, run by the generated factory — no reflection.

`@PostConstruct` marks the method invoked once the bean is fully built: after
the constructor, `@Inject` methods, and `@Inject` fields, as the last step of
construction.

```java
@Singleton
public final class Db {
    private final Config config;

    @Inject
    public Db(Config config) {
        this.config = config;
    }

    @PostConstruct
    void connect() {
        // every dependency is set; safe to use config here
    }
}
```

`@PreDestroy` marks the method invoked when the owning injector closes.
`Injector` is `AutoCloseable`, so the natural shape is try-with-resources:

```java
@Singleton
public final class Db {
    @PreDestroy
    void disconnect() { ... }
}
```

```java
try (Injector app = Injector.create()) {
    app.getInstance(Db.class);   // @PostConstruct connect() runs here, on first use
}                                // close(): @PreDestroy disconnect() runs
```

`close()` invokes `@PreDestroy` on every scoped instance the injector actually
created, in **reverse creation order** — so a bean is destroyed before the
dependencies it was built from. It is idempotent, and resolving through a closed
injector fails fast. If a callback throws, the remaining beans are still
destroyed and the first failure propagates with the rest attached as suppressed
exceptions.

Only instances that were created are destroyed: a singleton never resolved is
never touched. The container tracks nothing beyond scoped beans that declare a
`@PreDestroy`, so a prototype-heavy graph carries no bookkeeping.

The owning injector of a bean of a [child-local scope](child-injectors.md#child-local-scopes)
is the child that hosts it. Closing a child closes its live children first,
newest first, then destroys its own instances, in reverse creation order, with
the same handling of failures; closing the root closes its live children the
same way. After closing, resolving through the child fails fast, and so does
`get()` on any `Provider` obtained through it.

## Limitations

- `@PreDestroy` requires a scoped bean. On a prototype or an
  [assisted](assisted-injection.md) bean it is a compile error, not a silent
  no-op: those instances are caller-owned, and the container never sees them
  again to destroy them. A prototype that owns a resource should implement
  `AutoCloseable` and be closed by whoever requested it.
- `@PostConstruct` runs for constructor-built beans — including assisted beans
  and beans rebuilt through a [child injector](child-injectors.md). It does not
  run for [`@Provides`](provides-methods.md) results; the provider method already
  runs arbitrary code and can call the initializer itself.
- At most one `@PostConstruct` and one `@PreDestroy` per class. Each must be a
  non-private, non-static, no-argument `void` method declared on the bean
  itself; inherited callbacks are not collected.
- Closing a [child injector](child-injectors.md) never closes its parent. The
  beans of other scopes that a child resolves belong to the injector that
  declared them, and are destroyed when it closes; unscoped beans a child builds
  are never tracked.
