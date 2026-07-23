# Lifecycle callbacks — Design

`@PostConstruct` runs after a bean is fully injected; `@PreDestroy` runs for container-created scoped instances when
the injector closes. Zero reflection: generated factories call both.

## Goals

- A bean method annotated `@PostConstruct` is invoked as the last step of the build flow (construct → `@Inject`
  methods → fields → callback), for normal beans, assisted beans, and child-injector rebuilds alike.
- `Injector` becomes `AutoCloseable`; `close()` invokes `@PreDestroy` on every scoped instance this injector
  created, in reverse creation order (LIFO).
- Prototypes and assisted beans are caller-owned and never destroyed by the container — enforced at compile time,
  not silently ignored.

Out of scope (v1): inherited callbacks (superclass methods — consistent with member injection, which reads declared
members only); callbacks on `@Provides` products (the recipe body can call them; declared-type dispatch on possibly
subclassed instances is a trap); child-injector destruction (children own no scoped instances).

## User-facing API

Two annotations in `be.imgn.inject`, both `@Retention(RUNTIME)`, `@Target(METHOD)`:

```java
/** Marks the method invoked after the bean is fully injected. */
public @interface PostConstruct {}

/** Marks the method invoked when the owning injector closes; requires a scoped bean. */
public @interface PreDestroy {}
```

`Injector` extends `AutoCloseable` with `void close()` (no checked exception):

```java
@Singleton
public final class Db {
    @PostConstruct
    void connect() { ... }

    @PreDestroy
    void disconnect() { ... }
}

try (Injector app = Injector.create()) {
    app.getInstance(Db.class);
}   // close(): disconnect() on every created singleton, LIFO
```

## Semantics

- **`@PostConstruct` timing**: after construction, `@Inject` method injection, and field injection — the final step
  of `Factory.build(...)`; identically placed in assisted-factory `create(...)` bodies and in child-context
  rebuilds (which go through `Factory.build`).
- **`@PreDestroy` timing**: `close()` walks the injector's created scoped instances in reverse creation order and
  invokes each callback. The walk completes even when callbacks throw: the first exception propagates after the
  walk, later ones attached as suppressed. `close()` is idempotent; resolving through a closed injector throws
  `IllegalStateException`.
- **Tracking**: only scoped instances whose factory declares a `@PreDestroy` are tracked, at first creation.
  Nothing else is ever retained — no unbounded growth for prototype-heavy graphs.
- **Children**: `close()` on a child is a no-op for destruction (children own no scoped instances in v1) and never
  closes the parent. Closing the parent does not close children, but a closed parent makes their fallback
  resolutions fail with `IllegalStateException`.
- **Callback shape**: `void`, zero parameters, non-static, non-private (generated code in the bean's package calls
  it), at most one `@PostConstruct` and one `@PreDestroy` per class.

## Compile-time errors

| Case | Diagnostic contains |
|---|---|
| Callback method with parameters, a return value, `static`, or `private` | "callback" |
| Two `@PostConstruct` (or two `@PreDestroy`) methods on one class | "at most one" |
| `@PreDestroy` on an unscoped bean | "requires a scoped bean" |
| `@PreDestroy` on an assisted bean | "caller-owned" |
| `@PostConstruct`/`@PreDestroy` on a non-bean class's method | not an error — inert until the class becomes a bean (annotations are plain metadata) |

## Runtime model

- `Factory` gains two overridable no-op hooks:

```java
    /** Invoked by {@link #build} after field injection; generated factories call the bean's @PostConstruct. */
    protected void postConstruct(T instance) {}

    /** Invoked by the owning context on close; generated factories call the bean's @PreDestroy. */
    protected void preDestroy(T instance) {}

    /** {@return whether this factory's bean declares a @PreDestroy callback} */
    protected boolean hasPreDestroy() { return false; }
```

  `build(...)` calls `postConstruct(instance)` last. Generated factories override the hooks with direct calls
  (`instance.connect()`).
- `DefaultContext`: when wrapping a scoped factory with `hasPreDestroy()`, the scope-wrapped provider is further
  wrapped to record `(factory, instance)` into a synchronized LIFO deque on creation. `close()` (new on `Context`)
  drains it in reverse, invoking `factory.preDestroy(instance)` per entry with the collect-suppressed policy, then
  marks the context closed; `getProvider` checks the closed flag first. Second `close()` returns immediately.
- `ChildContext.close()`: marks itself closed (its own resolutions fail afterwards), destroys nothing, does not
  touch the parent.
- `DefaultInjector.close()` delegates to its context.
- The assisted-factory generator emits the `postConstruct` call at the end of `create(...)` for constructor
  recipes. Assisted beans cannot carry `@PreDestroy` (compile error above).

## Testing

- **Runtime (`inject`)**: `postConstruct` hook runs last in `build` (order pinned against method/field injection);
  `close()` invokes `preDestroy` LIFO across two singletons; untracked prototypes never invoke it; exception in the
  first callback still destroys the second and propagates with suppression; double-close no-op;
  resolution-after-close throws `IllegalStateException`; child close is a no-op that does not close the parent.
- **Processor golden**: generated factory overrides `postConstruct`/`preDestroy`/`hasPreDestroy`; assisted impl
  calls `postConstruct` in `create`; each error-table row errors with its fragment.
- **End-to-end example**: a classpath singleton with both callbacks exercised through try-with-resources.

## Decisions log

- Scoped-on-close destruction (Spring's honest line) over Guice's refusal and over track-everything (a leak by
  design); prototypes/assisted are caller-owned, enforced by compile error rather than silent no-op.
- Own annotations only — zero dependencies, one blessed spelling, consistent with every other framework annotation.
- One callback of each kind per class; declared members only.
- `@Provides` products excluded: the recipe body already runs arbitrary code and can call lifecycle methods itself.
