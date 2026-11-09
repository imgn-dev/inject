# imgn inject

imgn inject is a dependency injection framework for Java 25 or later that uses
no reflection: an annotation processor generates a factory for each bean at
compile time, and the injector finds those factories through `ServiceLoader`,
on the class path and on the module path. Every class is documented in the
[API documentation](https://imgn-dev.github.io/inject/api/latest/).

## Artifacts

| Artifact              | Module                     | What it does                                      |
|-----------------------|----------------------------|---------------------------------------------------|
| `inject`              | `be.imgn.inject`           | The annotations and the injector                  |
| `inject-processor`    | `be.imgn.inject.processor` | The annotation processor, and its [plugin SPI](processor-plugins.md) |
| `inject-maven-plugin` |                            | [Registers the generated factories](module-path.md) in `module-info.class` |

## Setting up

Depend on `inject`, and run `inject-processor` as an annotation processor:

```xml
<dependencies>
  <dependency>
    <groupId>be.imgn.inject</groupId>
    <artifactId>inject</artifactId>
    <version>${inject.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>be.imgn.inject</groupId>
            <artifactId>inject-processor</artifactId>
            <version>${inject.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

The processor claims no annotation, so a build compiling with
`-Xlint:all -Werror` needs `-Xlint:all,-processing` (see
[injection](injection.md#limitations)). On the module path, require
`be.imgn.inject` and declare the generated factories, by hand or with the
Maven plugin (see [module path](module-path.md)).

## Features

One page per feature. Each page states the problem the feature solves, how it
solves it, examples per use case, and its limitations.

### Declaring beans

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
- [Construction listeners](construction-listeners.md) — `ConstructionListener`
  contributions called with every instance the container builds.
- [Eager singletons](eager-singletons.md) — `@Eager` singletons built when the
  root injector is created.
- [Method interception](interception.md) — `@InterceptingAnnotation` triggers
  and `@Intercepts` beans running around method calls, wired by a generated
  subclass.

### Consuming beans

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

### Structuring graphs

- [Child injectors](child-injectors.md) — request- or tenant-scoped binding
  layers over a shared parent graph, and child-local scopes cached per child.
- [Builder overrides](builder-overrides.md) — replacing discovered bindings
  with explicit instances or providers, primarily for tests.

### Safety and tooling

- [Cycle detection](cycle-detection.md) — compile-time rejection plus a
  runtime guard with readable cycle paths.
- [Processor plugins](processor-plugins.md) — site-derived injected values via
  the `@InjectingAnnotation` / `InjectPlugin` SPI.
- [Classpath and module path support](module-path.md) — `ServiceLoader`
  discovery in both worlds, and the Maven plugin for module descriptors.
