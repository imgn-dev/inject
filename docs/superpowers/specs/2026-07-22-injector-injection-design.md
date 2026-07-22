# Injector injection & framework-type prohibitions — Design

Beans can inject the `Injector` that built them; the framework's own types — `Injector`, `Context`, `Factory` — can
never be bound, and `Context`/`Factory` can never be injected.

## Goals

- `@Inject` sites (constructor, field, method parameter) of type `Injector`, or `Provider<Injector>`, resolve to the
  same instance `Injector.create()` (or `Injector.builder().build()`) returned.
- Binding any of `Injector`, `Context`, `Factory` is rejected: at compile time where the processor can see it, at
  runtime in `Injector.Builder`.
- Injecting `Context` or `Factory` (raw or parameterized, directly or via `Provider`) is a compile error.
- `Context.create()`/`Context.create(Map)` are removed: they existed only as a cross-package construction bridge and
  carry no SPI value.

## Runtime model

`DefaultContext` becomes a public final class (it crosses the package boundary now); its no-argument and
overrides-map constructors become public, the test-oriented `(factories, scopes, ...)` constructors stay
package-private. `Context` becomes a pure interface: `getProvider` plus the introspection defaults.

```java
// be.imgn.inject.Injector
static Injector create() {
    return new DefaultInjector(new DefaultContext());
}

// Builder.build()
return new DefaultInjector(new DefaultContext(Map.copyOf(overrides)));

// be.imgn.inject.DefaultInjector
DefaultInjector(DefaultContext context) {
    this.context = context;
    context.setInjector(this);
}
```

`DefaultContext.setInjector(Injector)`:

- public, once-only — a second call throws `IllegalStateException`;
- the constructor pre-registers an internal provider under `Key.of(Injector.class)` that reads the injector field;
  resolving it before `setInjector` throws `IllegalStateException` ("no Injector attached to this Context") — this
  cannot happen through `Injector.create()`/`builder().build()`, only through a hand-built `DefaultContext` that
  never attached one;
- a bare `new DefaultContext(...)` in tests therefore supports everything except `Injector` resolution.

The `Injector` binding is built-in, not a bean: `getBeanDefinition(Key.of(Injector.class))` is empty and it appears
in no stereotype query.

`Injector.Builder.bind(...)` (both overloads) rejects a key whose raw type is `Injector`, `Context`, or `Factory`
with `IllegalArgumentException`.

## Processor

- **`Injector` sites**: an injection site of type `Injector` is an ordinary dependency rendered as
  `Key.of(Injector.class)`; `Provider<Injector>` composes through the existing unwrap. `PlanStage` demands no
  default factory (library type, not a root element — existing behavior).
- **Forbidden injections**: a site whose (Provider-unwrapped) type erasure is `Context` or `Factory` is a compile
  error: "Context cannot be injected; inject Injector instead." / "Factory cannot be injected; inject Injector or a
  Provider instead."
- **Forbidden bindings** — explicit naming of `Injector`, `Context`, or `Factory` is a compile error:
  - `@Provides` method whose return type erasure is one of the three;
  - `@ExposeAs` listing one of the three;
  - `@IntoSet`/`@IntoMap` whose element type is one of the three.
- **Incidental supertypes**: a bean whose supertype closure contains one of the three (e.g. a bean implementing
  `Context`) does not error; the three types join `EXCLUDED_DEFAULT_EXPOSURES`, so they are silently not exposed —
  consistent with `Serializable`/`Comparable` today. Explicit is an error; incidental is skipped.

## Compile-time errors

| Case | Diagnostic contains |
|---|---|
| Injecting `Context` (any shape, incl. `Provider<Context>`) | "inject Injector instead" |
| Injecting `Factory` (raw or `Factory<T>`, incl. via `Provider`) | "cannot be injected" |
| `@Provides` returning `Injector`/`Context`/`Factory` | "cannot be bound" |
| `@ExposeAs` listing `Injector`/`Context`/`Factory` | "cannot be bound" |
| `@IntoSet`/`@IntoMap` element type `Injector`/`Context`/`Factory` | "cannot be bound" |

## Testing

- **Runtime (`inject`)**: injected `Injector` is the same instance returned by `create()`; same through
  `builder().build()`; `Provider<Injector>` works; `Builder.bind` rejects all three types (both overloads);
  hand-built `DefaultContext` without `setInjector` throws `IllegalStateException` on `Injector` resolution;
  second `setInjector` throws.
- **Processor golden**: an `Injector` site renders `Key.of(Injector.class)` and compiles; each prohibition row above
  errors with its fragment; a bean implementing `Context` compiles with `Context` absent from its exposed keys.
- **End-to-end example**: a classpath example bean injects `Injector` and resolves another bean through it.

## Decisions log

- Self-binding via `DefaultContext.setInjector(...)` called from `DefaultInjector(DefaultContext)`; construction
  wiring stays in `Injector.create()`/`Builder.build()`.
- `Context.create()` statics removed; `DefaultContext` made public — the factory methods were only a package bridge.
- Explicit binding of framework types errors; incidental supertype exposure is silently excluded.
- Prohibition enforcement lives at compile time everywhere the processor sees, plus `Builder.bind` at runtime.
