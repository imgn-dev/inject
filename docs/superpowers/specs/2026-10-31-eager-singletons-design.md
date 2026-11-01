# Eager singletons — Design

A `@Singleton` bean or `@Provides` method marked `@Eager` is built when the root injector is created, instead of on
first resolution. Construction listeners see it like any other instance.

## Goals

- A singleton that nobody injects, but that must exist, gets built: an event subscriber, a registrar, a background
  service started from `@PostConstruct`.
- Eager singletons are reported to every `ConstructionListener`, so a framework that reacts to instances (Swing's
  event bus registration) sees them without extra wiring.
- Eagerness is a property of the bean, declared where the bean is declared. It needs no new `ServiceLoader` service
  type, and so no new `provides` directive on the module path.
- Every placement that cannot be honoured is a compile error, never a silent no-op.

Out of scope (v1):

- Eager beans of other scopes, including building child-local beans when a child hosting their scope is created.
- An ordering attribute. Dependencies are built first; beyond that the order is unspecified.
- Turning eagerness off for a whole injector (for example a test builder option).
- `@Eager` as a meta-annotation, so a framework annotation could imply it.
- Exposing eagerness through `BeanDefinition`.

Each of these can be added later without breaking anything.

## User-facing API

One annotation in `be.imgn.inject`:

```java
/** Marks a {@code @Singleton} bean or {@code @Provides} method to be built when the root injector is created. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Eager {}
```

Usage:

```java
@Eager
@Singleton
public final class AuditSubscriber {
    @Inject
    public AuditSubscriber(AuditLog log) { ... }

    @Subscribe
    void on(UserLoggedIn event) { ... }
}
```

```java
@Singleton
public final class Telemetry {
    @Provides
    @Eager
    @Singleton
    static Exporter exporter(TelemetryConfig config) { ... }
}
```

`@Eager` is not a stereotype: it carries none of the five meta-annotations, so it doesn't appear in
`AnnotationMetadata` and isn't returned by `getBeanDefinitionsByStereotype`.

## Semantics

- **When.** `Injector.create()` and `Injector.Builder.build()` build the eager singletons before returning the
  injector. This happens in `DefaultContext.setInjector`, right after the construction listeners are resolved.
- **Reporting.** Since the listeners already exist, every eager singleton is reported, and so is every instance
  built while resolving one (its dependencies). An eager singleton that a listener depends on is an exception: it is
  built while the listeners are resolved, before any of them exists, so it isn't reported, like every other listener
  dependency.
- **Order.** Each eager singleton's dependencies are built before it. Beyond that the order is unspecified (in
  practice, `ServiceLoader` order). Code that needs one singleton built before another depends on it.
- **Thread.** Eager singletons are built on the thread that creates the injector.
- **Which beans.** A bean is built eagerly when its factory is live in the root registry:
  - A bean whose condition fails is never built.
  - A `@Default` binding that lost its key to another binding isn't built.
  - A bean whose every exposed key is shadowed by a builder override isn't built: the override serves those keys.
  - A bean still serving at least one key is built.
  - A bean contributing with `@IntoSet` or `@IntoMap` is built through its contribution provider.
  - A bean whose supertype keys are ambiguous is still built: the ambiguity only affects those keys.
- **Child injectors.** Singletons anchor to the root, so creating a child builds nothing. Children see the instances
  the root already built.
- **Failure.** If an eager singleton fails to build, injector creation fails with that exception.
  - Before rethrowing, the context is closed, so `@PreDestroy` runs on the singletons already built, in reverse
    creation order.
  - The same handling covers a failure while resolving the construction listeners, since both happen in
    `setInjector`.

## Compile-time errors

The processor rejects:

- `@Eager` on a type or `@Provides` method that isn't `@Singleton`. That includes unscoped beans, child-local scopes,
  and custom scopes with a `Scope` service: the processor can't tell a custom scope's lifetime, and eager
  construction only makes sense for one instance per root injector.
- `@Eager` on a method that isn't `@Provides`.
- `@Eager` on an `@AssistedFactory` interface, or on an assisted product (a type with an `@Assisted` constructor
  parameter): neither is a registry bean the root can build without arguments.
- `@Eager` on an interface, an abstract class, an enum or an annotation type.

`@Eager` makes a concrete class a bean, as `@Intercepts` does, so it needs no `@Inject` member. The class is built
through its `@Inject` constructor, or else through its accessible no-argument constructor; without either, the processor
reports an error. `@Singleton` counts whether it's declared on the class or inherited from a supertype, following the
usual scope inheritance.

## Runtime model

- `Factory` gains `protected boolean isEager()`, which returns `false` by default. The generated factory of an
  `@Eager` bean or `@Provides` method overrides it to return `true`. This is an additive change to the exported SPI:
  factories generated by an older processor keep compiling and running, and are never eager.
- `DefaultContext`'s constructor records, for each live eager factory, the provider it registered. That's the scoped
  provider, or the contribution's provider for a contributing factory. Once overrides and defaults are resolved, the
  constructor drops factories no longer in the definition list, as described in [Which beans](#semantics).
- `setInjector` resolves the listeners, then calls `get()` on each recorded provider. Resolution goes through the
  usual cycle guard and singleton cache, so a later `getInstance` returns the same instance.
- `setInjector` runs this start-up through `Destroyables.runAll`, followed by an action that closes the context
  when start-up did not complete. The start-up failure is thrown, with any failure to close suppressed on it.
  `Injector.create()` and `Builder.build()` are unchanged apart from their `@throws`.

## Swing's use

Swing's subscriber singletons carry `@Eager` next to `@Singleton`. Swing's processor can then report an error, or a
warning, for a `@Singleton` with `@Subscribe` methods that isn't `@Eager`, so a missing annotation is caught at
compile time. Swing's listener resolves the bus through `Provider<EventBus>` (see the construction listener
limitations), and the bus must not depend on any subscriber.

## Testing

- Processor tests: each compile error above, with its message; the generated `isEager()` override, for a bean class
  and for a `@Provides` method.
- Runtime tests:
  - An `@Eager` singleton nobody resolves gets built at creation, its `@PostConstruct` runs, and it's reported to a
    listener, along with its dependencies.
  - A later `getInstance` returns the same instance.
  - A non-eager singleton stays unbuilt.
  - An eager bean whose condition fails isn't built.
  - A losing `@Default` isn't built.
  - A bean fully shadowed by a builder override isn't built.
  - An eager `@IntoSet` contributor gets built.
  - An eager bean that fails makes `create()` throw, and an already built eager singleton with `@PreDestroy` is
    destroyed.
  - Creating a child builds nothing new.
- An end-to-end example in `inject-examples`, if a natural place exists. Otherwise the feature doc's example is
  covered by a runtime test.

## Documentation

- New `docs/src/main/markdown/eager-singletons.md`, linked from `docs/src/main/markdown/index.md`.
- `construction-listeners.md`: a line saying eager singletons are reported.
- Javadoc on `@Eager`, `Factory.isEager()`, `Injector.create()` and `Builder.build()` (`@throws`).
- A changelog entry.
