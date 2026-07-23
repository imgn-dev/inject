# `@Provides` Methods

## The problem

Constructor injection requires annotating the class itself. That is impossible
for types you do not own (`String`, `Clock`, anything from a library) and
awkward for values that need assembly logic — reading configuration, choosing a
variant, composing several inputs into one object.

## How it solves it

A method annotated `@Provides` becomes a binding: its return type (plus
qualifier, if any) is the key, its body is the recipe, and its parameters are
injected like constructor parameters.

```java
public final class Config {
    private Config() {}

    @Provides
    @Named("greeting")
    static String greeting() {
        return "Hello";
    }

    @Provides
    static Clock clock() {
        return Clock.systemUTC();
    }
}
```

Any class can host `@Provides` methods:

- **Static method** — called directly; the host class is never instantiated.
- **Instance method** — the host is resolved through its own factory first,
  so the host's own dependencies are injected before the method runs.

`@Provides` methods compose with the rest of the framework: they accept
qualified parameters, can be scoped (`@Singleton` on the method), can
contribute to [collections](multibindings.md) (`@IntoSet` / `@IntoMap`), can be
[conditional](conditional-beans.md), and can serve as
[assisted-injection recipes](assisted-injection.md).

```java
public final class Wiring {
    @Provides
    @Singleton
    static DataSource dataSource(@Named("jdbc.url") String url) {
        return DataSource.open(url);
    }
}
```

## Limitations

- A `@Provides` method is a build recipe, not a managed bean: members of the
  returned object are not injected afterwards. The method body must fully
  finish the object.
- Two unconditioned bindings for the same key — whether from `@Provides` or
  `@Inject` classes — are a `DuplicateBindingException` at injector creation.
  There is no priority order between competing bindings.
- The host of an instance `@Provides` method must itself be constructible by
  the framework, which makes static methods the simpler default.
