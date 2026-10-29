# Design — `be.imgn.inject` Dependency Injection Framework

**Date:** 2026-07-18
**Status:** Approved design, pre-implementation

## 1. Overview

A dependency injection framework for Java 21 that runs on both the classpath and
the module path. It performs **zero reflection**: all injection is done through
`Factory` subclasses generated at compile time by an annotation processor, one
factory per injectable class. Because there is no reflection, member visibility
is enforced at generation time — a generated factory is emitted into the bean's
own package so it can legally touch package-private constructors, methods, and
fields.

Injection is supported on constructors, setter/instance methods, and fields, each
annotated with `@Inject`. Types the user does not control (e.g. `String`) are
supplied via `@Provides` methods. The processor is pluggable: custom injecting
annotations (e.g. `@Log`) are handled by plugins discovered through
`ServiceLoader`.

### Core design decisions

| # | Decision |
|---|----------|
| Default scope | **Prototype** — a new instance per `get()`. Scopes are opt-in wrappers. |
| Cycles | **Compile-time detection** in the processor **plus a runtime guard** in `Context`. Constructor cycles are always fatal. |
| Scope binding | A `@ScopingAnnotation` pairs with a `Scope` SPI implementation, discovered by `ServiceLoader` and keyed by `annotationType()`. A scoping annotation with no `Scope` is child-local: the child injector that hosts it caches its beans. |
| Qualifier identity | Annotation **type + attribute values** (`@Named("db")` ≠ `@Named("cache")`; marker qualifiers match by type alone). |
| `@Provides` host | **Any class.** Static method → called directly. Instance method → host resolved via its own factory first. |
| `Provider<T>` injection | **Supported** — a `Provider<Foo>` dependency injects the provider itself (deferred/repeated `get()`). |
| Validation | **Compile-time where the processor can see it; at injector creation for the rest**: every live bean's dependencies must be bound, by a discovered factory or a builder binding. |
| Concurrency | **Thread-safe.** `Injector`/`Context` registry immutable post-construction; `SingletonProvider` instantiates exactly once under contention. |

## 2. Module Layout

| Module | Coordinates | Contents |
|--------|-------------|----------|
| parent | `be.imgn.inject:inject-parent` | pom aggregator; inherits `be.imgn.parent:parent`, imports `be.imgn.parent:bom`; Java 21 |
| runtime | `be.imgn.inject:inject` | public API + annotations + runtime engine. `module be.imgn.inject` |
| processor | `be.imgn.inject:inject-processor` | annotation processor (gaia-processing); depends on `inject` |
| log | `be.imgn.inject:inject-log` | the `@Log` plugin annotation; depends on `inject` only |
| log processor | `be.imgn.inject:inject-log-processor` | `LogPlugin`, the processor side of `@Log` |
| maven plugin | `be.imgn.inject:inject-maven-plugin` | registers generated factories as `module-info` services |
| examples | `be.imgn.inject:inject-examples` | classpath usage + integration tests; `@Log` plugin demo |
| examples (module path) | `be.imgn.inject:inject-examples-module` | module-path usage + integration tests |

`inject-processor` and `inject-log-processor` belong on a consumer's annotation processor path, never on its
classpath.

## 3. Package Structure

### `be.imgn.inject` — public, user-facing
- `Key<T>`, `Injector`, `Provider<T>`
- Annotations: `@Inject`, `@Provides`, `@InjectingAnnotation`, `@QualifyingAnnotation`,
  `@ScopingAnnotation`
- Built-ins: `@Singleton` (a `@ScopingAnnotation`), `@Named` (a `@QualifyingAnnotation`)

### `be.imgn.inject.context` — public types, conceptually internal
- `Context`, `Factory<T>`, `FactoryProvider<T>`
- `InjectionPoint` + `ConstructorInjectionPoint`, `MethodInjectionPoint`, `FieldInjectionPoint`, `Parameter`, `AnnotationMetadata`
- `Scope` SPI, `SingletonProvider`

These types are `public` only so generated factory subclasses (which live in the
bean's package) can extend `Factory` and call the engine. Users are not expected
to touch `be.imgn.inject.context` directly.

## 4. Public API (`be.imgn.inject`)

### `Key<T>` — final class
```java
public final class Key<T> {
    Type type();                                        // java.lang.reflect.Type
    @Nullable Qualifier qualifier();                    // null == unqualified
    boolean hasQualifier();
    static <T> Key<T> of(Class<T> type);
    static <T> Key<T> of(Class<T> type, @Nullable Qualifier qualifier);
    static Key<?> of(Type type, @Nullable Qualifier qualifier);
}
```
- A `Key` is a `final class` (not a hierarchy): a `java.lang.reflect.Type` plus a
  **nullable** `Qualifier` (`null` denotes an unqualified key). Identity is type +
  qualifier; `equals`/`hashCode` cover both.
- The `Type` overload of `of` accepts parameterized types (e.g. from a `Parameter`);
  the `Class` overloads are the common case. `Class` being more specific than `Type`,
  a `Class` argument always binds to the `Class` overload and preserves `Key<T>`.
- `Qualifier` — value object carrying the qualifier annotation type **and its
  attribute values**; `equals`/`hashCode` cover both. Marker qualifiers carry no
  values and match by type alone.

### `Provider<T>`
```java
@FunctionalInterface
public interface Provider<T> { T get(); }
```
The single core abstraction. Everything is a provider.

### `Injector`
```java
public interface Injector {
    <T> T getInstance(Class<T> type);
    <T> T getInstance(Key<T> key);
    <T> Provider<T> getProvider(Class<T> type);
    <T> Provider<T> getProvider(Key<T> key);   // the real business method

    static Injector create();                   // ServiceLoader bootstrap
}
```
The first three methods delegate to `getProvider(Key)`. `create()` builds a
`Context`, which loads all registered factories and scopes.

### Annotations
- `@Inject` — targets constructor, method, field.
- `@Provides` — targets method only.
- `@InjectingAnnotation`, `@QualifyingAnnotation`, `@ScopingAnnotation` — **meta-annotations**.
  Each is declared `@Target(ElementType.ANNOTATION_TYPE)` — they are allowed
  **exclusively on other annotations**, never on a method, parameter, field, class,
  or constructor. `javac` rejects direct misuse via the `@Target`, and the
  processor additionally reports a clear compile error if one is found anywhere but
  an annotation declaration. A user defines an annotation, meta-annotates *it* with
  one of these, then annotates code with the user annotation.
  - A **qualifying annotation** = any annotation meta-annotated with
    `@QualifyingAnnotation` (built-in: `@Named`). Contributes to a `Key`'s qualifier.
  - A **scoping annotation** = any annotation meta-annotated with
    `@ScopingAnnotation` (built-in: `@Singleton`). Selects the `Scope` wrapper.
  - An **injecting annotation** = any annotation meta-annotated with
    `@InjectingAnnotation`. Handled by a plugin (e.g. `@Log`).

  Wherever this spec says a bean, field, parameter, or `@Provides` method "carries a
  qualifier/scope", it means it is annotated with such a meta-annotated annotation.

## 5. Runtime Engine (`be.imgn.inject.context`)

### `Context`
Richer than `Injector`; internal but accessible to `Factory`.
- At construction: loads all `Factory` services **and** all `Scope` services via
  `ServiceLoader`. Builds an immutable `Map<Key, Provider>` of top-level providers.
- For each factory it wraps a `FactoryProvider` with the `Scope` declared by that
  factory (default prototype = no wrapper).
- Exposes resolution to factories: `<T> Provider<T> getProvider(Key<T>)`.
- **Lazy provider tree:** dependency-edge providers are resolved on demand and
  memoized; the whole graph is not eagerly wired (consistent with prototype
  default and fail-at-first-resolution validation).
- **Runtime cycle guard:** a thread-local resolution stack; re-entry on an
  in-progress key throws with the full `A → B → A` path.
- **Thread-safe:** registry immutable after construction.

### `FactoryProvider<T>`
Holds a `Context` and a `Factory<T>`. `get()` = `factory.build(context)`.

### `Scope` SPI
```java
public interface Scope {
    Class<? extends Annotation> annotationType();
    <T> Provider<T> wrap(Provider<T> provider);
}
```
- Discovered via `ServiceLoader`, keyed by `annotationType()`.
- `SingletonProvider` = the wrapper returned by the built-in singleton scope;
  double-checked locking (or holder) so `get()` instantiates exactly once under
  contention.
- A project registers its own `Scope` implementations in
  `META-INF/services/be.imgn.inject.context.Scope`; the processor writes only the
  `Factory` file. Custom scopes are user-definable.
- A scoping annotation with no `Scope` service is child-local: a child injector
  built with `scopedChildBuilder(scope)` hosts it and caches its beans.

### `Factory<T>` — abstract base
```java
public abstract class Factory<T> {
    protected Factory(Class<T> type);
    public final Class<T> type();
    public final T build(Context context);          // create + injectMethods + injectFields
    protected abstract T createInstance(Context context);
    protected void injectMethods(T instance, Context context);   // empty default
    protected void injectFields(T instance, Context context);    // empty default
    protected final <D> Provider<D> getProviderForConstructor(int paramIndex, Class<? super D> type, Context context);
    protected final <D> D getInstanceForConstructor(int paramIndex, Class<? super D> type, Context context);
    // getProviderForMethod / getInstanceForMethod / getProviderForField / getInstanceForField
    protected List<Key<?>> exposedKeys();           // default: List.of(Key.of(type))
}
```
- Injection points form a sealed hierarchy `InjectionPoint permits
  ConstructorInjectionPoint, FieldInjectionPoint, MethodInjectionPoint`. Each carries
  a back-reference to its owning `Factory<?>` and an `AnnotationMetadata` (annotations
  present at the site — currently a stub exposing `empty()`, to be enriched for
  plugins). Constructor/method points hold an ordered `List<Parameter>` and expose
  `keyForParameter(int)`; field points hold `fieldName`/`fieldType`/nullable
  `Qualifier`/`isProvider` and expose `key()`. Used for resolution and inspection.
- `Parameter` (name, `java.lang.reflect.Type`, nullable `Qualifier`,
  `AnnotationMetadata`, `isProvider`) describes one constructor/method argument;
  `keyForParameter`/`key` build the `Key` from the type + qualifier.
- **`Provider<T>` dependency types are recognized:** a parameter/field declared as
  `Provider<Foo>` has `isProvider = true` and its `type`/`fieldType` set to the inner
  `Foo`; the generated factory injects the provider itself (via
  `getProviderFor…`) rather than a resolved `Foo` (via `getInstanceFor…`).
- **Resolution calls name the dependency type twice.** Generated code calls
  `this.<Foo>getInstanceForConstructor(0, Foo.class, context)`. The explicit type
  argument gives the call an exact static type, so javac selects the injected
  overload when the bean has constructors or methods of the same arity; it is the
  declared type, boxed, or its erasure when the factory cannot name it (a type
  variable or a wildcard). A primitive dependency is cast back
  (`(int) this.<Integer>…(0, int.class, context)`) so that `m(int)` is still chosen
  over `m(Integer)`. The class literal is the erasure: the helper checks that it is
  the erasure of the injection point's declared type, and that every resolved value
  is an instance of it (a primitive's wrapper, never `null`), throwing
  `InjectionException` naming the bean, the injection point and both types.

## 6. Annotation Processor (`inject-processor`)

Built on `be.imgn.gaia:gaia-processing`. `InjectProcessor` is a gaia `StepProcessor`
with one step, `InjectStep`, which receives the elements annotated with the
framework's annotations (`@Inject`, `@Provides`, `@Intercepts`, `@AssistedFactory`,
`@ExposeAs`, `@IntoSet`, `@IntoMap`, `@Default`) and those carrying an annotation
meta-annotated with `@InjectingAnnotation` or `@InterceptingAnnotation`. The
processor therefore supports `*`, and claims nothing. An element whose signature
names a type not generated yet, typically by another processor, is held back
until a later round resolves it. gaia's `source` package models the generated
code, `GeneratedSources` writes it in ASCII, and `ProcessingContext` reads
annotation values and reports diagnostics. Plugins are discovered via
`ServiceLoader<InjectPlugin>`.

### Pipeline (4 stages)

**1. Gather.** Collect every `@Inject` site, every `@Provides` method, and every
meta-annotated annotation. For each *dependency type* encountered, ensure a factory
will exist:
- has `@Inject`, is produced by `@Provides`, or is exposed by a bean, in this
  round or an earlier one → that binding's factory serves it, and no other factory
  is generated for it;
- not compiled from source in this compilation (a library or another module) → no
  factory here; it is bound elsewhere, checked at runtime;
- an interface or abstract class → no factory; an implementation must be bound
  elsewhere, checked at runtime;
- otherwise **must have an accessible no-arg constructor** → a default factory is
  generated for it (a dependency does **not** need `@Inject`, but it always needs a
  Factory);
- otherwise → no factory; it must be bound elsewhere (typically `Injector.builder().bind`),
  checked when the injector is created.

Plugins may contribute virtual injection points here (e.g. `@Log` → `Logger(Bean.class)`).

**2. Plan.** Build the dependency graph; group by target class (one factory per
class); resolve qualifier/scope assignment; **detect cycles** (compile-time half of
the cycle policy); validate no missing or duplicate keys among the generated set;
compute each factory's scope and `exposedKeys`. Plugins may rewrite/validate the plan.

**3. Generate.**
- `BeanFactoryGenerator` — one factory per `@Inject`/injectable class; emits
  `createInstance`, overrides `injectMethods`/`injectFields` as needed, `exposedKeys`;
  emitted **into the bean's package**.
- `ProvidesFactoryGenerator` — one factory per `@Provides` method; static method →
  direct call; instance method → resolve the host via its own factory, then call.
  A `@Provides` method may itself carry a **qualifying annotation** (a user
  annotation meta-annotated with `@QualifyingAnnotation`, e.g. `@Named`) and/or a
  **scoping annotation** (meta-annotated with `@ScopingAnnotation`, e.g.
  `@Singleton`): the qualifier becomes part of the produced `Key` (via
  `exposedKeys`), and the scope is the one `Context` wraps the resulting
  `FactoryProvider` with — exactly as for `@Inject` beans.

Plugins may emit code.

**4. Write.** Flush all `JavaFileObject`s and register each `Factory` as a
`ServiceLoader` provider.

### Generated type naming
Generated types live in the **same package** as the type they are generated for,
except annotation implementations (below). The simple name is built from the enclosing-class chain (outermost → target),
joined by a single `$`, prefixed with `$`, and suffixed with `$$<Kind>`. The
names come from gaia-processing's `ClassName.generatedName`; a `$` inside a name
is written `$0`:

| Source | Generated factory |
|--------|-------------------|
| `Bean` | `$Bean$$Factory` |
| `Bean.SubBean` (nested) | `$Bean$SubBean$$Factory` |
| `A.B.C` (deeper nesting) | `$A$B$C$$Factory` |

- **`@Provides`** factories append `$$` and the method name to the host chain,
  suffix `$$Factory`: a method `provideX` on host `Config` →
  `$Config$$provideX$$Factory`, distinct from the factory of a nested class
  `Config.provideX` (`$Config$provideX$$Factory`). An overloaded method adds its
  1-based position among the host's methods of that name, in declaration order
  (`$Config$$provideX$2$$Factory`).
- **Annotation implementations** go in the package of the factory that uses them,
  and start with the annotation's own package between `$$` markers: `Named` from
  `com.example` → `$$com$example$$Named$$Impl`.

### Service registration & conflict rule
- **Classpath:** the processor generates `META-INF/services/be.imgn.inject.context.Factory`.
  The processor owns these files exclusively — if a matching
  service file **already exists on the source path** (hand-authored or checked in),
  **compilation fails** to prevent stale/duplicate registration.
  gaia's `ServiceFiles` writes the file after the last round: entries sorted, each
  checked to be loadable by `ServiceLoader`, merged with the file an incremental
  build left in the class output, minus the entries whose class no longer exists.
- **Module path:** the generated service files are honored by JPMS only if the
  consumer module declares the corresponding `provides` directives. A processor
  cannot edit the user's `module-info.java`, so it emits a **compile-time note**
  listing the exact `provides be.imgn.inject.context.Factory with …` directives to add.

### Plugin SPI
```java
public interface InjectPlugin {
    Optional<CodeBlock> initializer(InjectionSite site);
}
```
Discovered by the processor via `ServiceLoader`. For each field or parameter
carrying an annotation meta-annotated `@InjectingAnnotation`, the processor asks
each plugin for the expression that initializes it; a plugin declines a site with
`Optional.empty()`. `CodeBlock` is `be.imgn.gaia.processing.source.CodeBlock`.

## 7. Module Path vs Classpath

- **Classpath:** auto-generated `META-INF/services` entries drive `ServiceLoader`.
- **Module path:** the same service files work only alongside `provides` directives
  in the consumer's `module-info.java`; the processor documents them via a note
  (it cannot write them). `inject-examples` demonstrates both a classpath run and a
  module-path run with hand-written `provides`.

## 8. Testing

- **Framework:** JUnit 6 + AssertJ 3.27.7.
- **Runtime unit tests:** `Key` equality (type + nullable qualifier); `SingletonProvider`
  once-only under concurrency; scope wrapping; runtime cycle guard;
  `Provider<T>` injection.
- **Processor tests:** an in-process `javax.tools` compiler harness
  (`TestCompiler`, with AssertJ `CompilationAssert`) — assert generated sources and
  compile errors (missing factory, cycle, no default constructor, `@Provides` on a
  non-method, pre-existing service file conflict).
- **`inject-examples`:** end-to-end on both classpath and module path; a `@Log`
  plugin as the plugin-system integration test, showcasing `Logger(<Injectee>.class)`
  injection.

## 9. Input Validation & Exceptions

**Every public entry point validates all inputs and throws an appropriate
exception — no method silently accepts bad input.**

### Rules
- **Null arguments** → `NullPointerException` via `Objects.requireNonNull(arg, "name")`
  on every public/protected method parameter (`Key.of`, `Injector.*`, `Factory`
  constructor and resolution helpers, `Scope.wrap`, `Provider` inputs, plugin hook
  arguments).
- **Out-of-range / malformed arguments** → `IllegalArgumentException` (e.g. a
  negative `paramIndex` in `getProviderForConstructor`, a `Class` that is not the
  factory's declared `type`, an empty qualifier name).
- **Illegal state at call time** → `IllegalStateException` (e.g. resolving against a
  `Context` mid-teardown).
- Validation happens **at the boundary**, before any work; messages name the
  offending argument.

### Framework exception hierarchy (`be.imgn.inject`)
```java
public class InjectionException extends RuntimeException { … }        // base, unchecked

public class UnsatisfiedDependencyException extends InjectionException // no factory for a Key
public class CircularDependencyException  extends InjectionException   // runtime cycle guard; carries the A → B → A path
public class DuplicateBindingException    extends InjectionException   // two providers for one Key
public class ScopeResolutionException     extends InjectionException   // several Scopes for one annotation; a child-local bean with no host, or in a collection
```
- Runtime resolution failures throw the specific subtype, always with a descriptive
  message (offending `Key`, resolution path, or conflicting factories).
- **Processor-side** validation (the compile-time half) reports through
  `Messager.printMessage(ERROR, …)` with the source element attached — a failed
  build, not a thrown exception. The pre-existing-service-file conflict (§6) is
  reported this way.

### Testing
Validation is covered by the JUnit 6 + AssertJ suite: `assertThatThrownBy(...)`
/ `assertThatNullPointerException()` for each boundary, and
the `javax.tools` compiler harness for the processor-side errors.

**AssertJ style:** use AssertJ's fluent chaining — one `assertThat(instance)`
followed by chained assertions (`.isNotNull().returns(…, from(…)).satisfies(…)`),
not twenty separate `assertThat(instance)` calls on the same object. Use
`.extracting(…)`, `.satisfies(…)`, and soft assertions where a single subject has
many facets.

## 10. Build & Tooling

**Build command: `./mvnw clean verify` — never `install`.** The Maven wrapper is
committed; all invocations use `./mvnw`.

`inject-parent` inherits the shared `be.imgn.parent:parent` POM and imports `be.imgn.parent:bom`, which supply
plugin and library versions and the quality gates. The project overrides only:

- **Java release** — `java.release=21`; the shared parent defaults to a newer release.
- **gaia** — `be.imgn.gaia:gaia-bom` is imported next to `be.imgn.parent:bom`, at `gaia.version`. The processor
  modules use `gaia-processing` to generate code and `gaia-processing-testing` to compile sources in tests.
- **Error Prone extras** (`errorprone.extra.args`) — NullAway `ExcludedFieldAnnotations` for `@Inject` and every
  consumed injecting-annotation plugin's annotation (`@Log`); `module-info.java` added to `-XepExcludedPaths`, since
  module descriptors name services and providers by their fully qualified names; a few checks switched off.
- **Checkstyle suppressions** — `checkstyle-suppressions.xml` restates the parent's suppressions (the plugin reads a
  single file) and adds `IllegalThrows` for the `Throwable`-declaring interception API. Other deliberate exceptions
  are `@SuppressWarnings("checkstyle:…")` at the site.
- **Javadoc** — `doclint all` (the parent omits `missing`), `failOnWarnings`, bound to `package` with goal
  `javadoc-no-fork`, so an undocumented or malformed public API member fails the build, consistent with the
  docs-always-current rule in `CLAUDE.md`. The two example modules set `maven.javadoc.skip`: they are read as
  source, not as API.
- **JaCoCo** — agent and report, which the parent does not configure.
- **Surefire** — `--sun-misc-unsafe-memory-access=allow`, chained with JaCoCo's `@{argLine}`.

The two example modules add `-Xlint:-processing`: the inject processor reads every annotation and claims none, which
javac's `processing` lint would otherwise report under `-Werror`.

Pipeline (single `./mvnw clean verify`): Enforcer → Spotless → Checkstyle (`validate`) → javac with `-Xlint:all
-Werror`, Error Prone + NullAway (`compile`) → Surefire + JaCoCo (`test`) → Javadoc (`package`) → dependency analysis
(`verify`). The enforcer's `reactorModuleConvergence` rule rejects partial reactors, so `-pl` builds fail unless they
include the root.

## 11. Bootstrap Summary

`Injector.create()` → constructs `Context` → `Context` `ServiceLoader`s all
`Factory` and `Scope` providers → wraps each `FactoryProvider` per its declared
scope → checks that every live bean's injection points (except those a plugin
supplies) name a bound key, throwing one `UnsatisfiedDependencyException` that
lists every missing dependency → returns a thread-safe `Injector` over the immutable
registry. Resolution is lazy and memoized; a key requested directly but never
bound, or a cyclic key, fails fast with a descriptive path.
