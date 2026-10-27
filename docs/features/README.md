# Features

One page per feature. Each page states the problem the feature solves, how it
solves it, examples per use case, and its limitations.

## Declaring beans

- [Zero-reflection injection](injection.md) — `@Inject` constructors, methods,
  and fields, wired by compile-time generated factories.
- [`@Provides` methods](provides-methods.md) — bindings for types you do not
  own or that need assembly logic.
- [Qualifiers](qualifiers.md) — distinguishing bindings of the same type;
  `@Named` and custom qualifier annotations.
- [Type exposure and `@ExposeAs`](type-exposure.md) — which types a bean is
  injectable as.
- [Scopes](scopes.md) — prototype by default, `@Singleton`, and the custom
  scope SPI.
- [Conditional beans](conditional-beans.md) — beans present only when a
  condition matches; `@ConditionalOnProperty` and the condition SPI.
- [Lifecycle callbacks](lifecycle-callbacks.md) — `@PostConstruct` after full
  injection and `@PreDestroy` on injector close.
- [Method interception](interception.md) — `@InterceptingAnnotation` triggers
  and `@Intercepts` beans running around method calls, wired by a generated
  subclass.

## Consuming beans

- [`Provider<T>` injection](provider-injection.md) — deferred and repeated
  resolution.
- [Keys and generic types](keys-and-generics.md) — `Key`, `TypeLiteral`, and
  `Types` for qualified and parameterized lookups.
- [Multibindings](multibindings.md) — `@IntoSet` / `@IntoMap` contributions
  aggregated into injectable `Set` and `Map` bindings.
- [Assisted injection](assisted-injection.md) — factories mixing container
  dependencies with caller-supplied values.
- [Injector injection and introspection](injector-injection.md) — resolving
  through the `Injector` itself; probing and enumerating bean definitions.

## Structuring graphs

- [Child injectors](child-injectors.md) — request- or tenant-scoped binding
  layers over a shared parent graph, and child-local scopes cached per child.
- [Builder overrides](builder-overrides.md) — replacing discovered bindings
  with explicit instances or providers, primarily for tests.

## Safety and tooling

- [Cycle detection](cycle-detection.md) — compile-time rejection plus a
  runtime guard with readable cycle paths.
- [Processor plugins](processor-plugins.md) — site-derived injected values via
  the `@InjectingAnnotation` / `InjectPlugin` SPI.
- [Classpath and module path support](module-path.md) — `ServiceLoader`
  discovery in both worlds, and the Maven plugin for module descriptors.
