# Multibindings — Design

Bind several beans into one collection and inject the collection: `Set<T>` and `Map<String, T>`, contributed
explicitly, aggregated at runtime.

## Goals

- A bean (or `@Provides` method) can contribute to a collection binding instead of binding on its own keys.
- An injection point can request `Set<T>`, `Map<String, T>`, or `Provider<` of either.
- The one-binding-per-key invariant, duplicate detection, scopes, and conditional beans keep working unchanged.

Out of scope (v1, all relaxable later without breaking): `List<T>`, `Set<Provider<T>>`, qualified collections,
non-String map keys, several markers on one bean, compile-time cycle edges through collections.

## User-facing API

Two annotations in `be.imgn.inject`, both `@Target({TYPE, METHOD})` and `@Retention(RUNTIME)`:

```java
@IntoSet(Handler.class)                          // element type
public final class AuthHandler implements Handler { @Inject public AuthHandler() {} }

@IntoMap(value = Handler.class, key = "auth")    // element type + String key
public final class AuthHandler implements Handler { ... }
```

Consumption is plain injection at any site kind:

```java
@Inject Router(Set<Handler> all) { ... }
@Inject Dispatcher(Map<String, Handler> byName, Provider<Set<Handler>> lazy) { ... }
```

Plus one public utility, `be.imgn.inject.Types`: `setOf(Class<?>)` and `mapOf(Class<?>, Class<?>)` return a
`ParameterizedType` whose `equals`/`hashCode` are structural and match the JDK implementation, so a key built by
generated code equals one captured through `TypeLiteral<Set<Handler>>`. `ParameterizedType` specifies no equality
contract; owning the implementation removes the cross-implementation risk (the same reason Guice ships `MoreTypes`).

### Semantics

- A marked bean binds **only** into its collection key: no plain key, no default supertype closure.
- One marker per bean, non-repeatable.
- Scopes apply per contributor: a `@Singleton @IntoSet` bean is one instance, shared through the set.
- Conditions apply per contributor: a contributor withheld by `@ConditionalOnProperty` is simply absent from the
  collection.
- Set iteration order and map ordering are unspecified (Guice semantics).
- Zero registered contributions means the collection key does not exist: injecting it throws
  `UnsatisfiedDependencyException` with the requester chain, like any missing binding. This is deliberate fail-fast:
  disabling the last contributor by condition fails the consumer's boot rather than silently injecting empty.

### Compile-time errors

| Case | Diagnostic |
|---|---|
| Marker combined with `@ExposeAs` | contradictory exposure |
| Marker combined with a qualifying annotation | collections are unqualified |
| Element type not assignable from the bean / `@Provides` return type | wrong element type |
| `@IntoMap` with a blank key | key required |
| Marker on a method not annotated `@Provides` | mirrors the `@ExposeAs` rule |
| Two in-compilation `@IntoMap` contributors with the same element type and key | duplicate map key |
| Injection site with raw `Set`/`Map`, or `Map` with a non-String key type | unsupported shape |

## Runtime model

New record and `Factory` hook:

```java
// be.imgn.inject.context
public record Contribution(Key<?> collectionKey, @Nullable String mapKey) {}

// Factory — overridable, default empty:
public Optional<Contribution> contribution() { return Optional.empty(); }
```

A generated marked factory overrides `exposedKeys()` to return `List.of()` and `contribution()` to return the
collection key (`Key.of(Types.setOf(Handler.class))`, with the map key for `@IntoMap`).

`DefaultContext` build, after condition filtering:

1. Factories with a contribution are grouped by collection key; the rest follow the existing path unchanged.
2. Every contributor is wrapped through the existing `wrap(...)` — per-contributor cycle guard and scope.
3. Each set group registers one provider under its collection key whose `get()` resolves every contributor into an
   immutable `Set`. Prototype contributors yield fresh instances per `get()`; singletons are shared.
4. Each map group does the same into an immutable `Map`; a duplicate map key across contributors (cross-module
   included) throws `DuplicateBindingException` at build.
5. A direct binding of the same collection type (for example a `@Provides Set<Handler>`) alongside contributions
   collides in the registry and throws the existing `DuplicateBindingException`.

Introspection: contributors still appear in `getBeanDefinitionsByStereotype` (they are definitions); the collection
key itself has no `BeanDefinition` — it is an aggregate, not a bean, and `getBeanDefinition` returns empty for it.

Cycles through a collection (contributor depends on a bean that injects the collection) are caught at runtime by the
existing cycle guard, with the full path in the message.

## Processor

- **Site detection**: `ModelFactory.dependency(TypeMirror)` learns `Set<E>` and `Map<String, V>` alongside the
  existing `Provider<T>` unwrap (which composes: `Provider<Set<E>>` works). The dependency's reflective type is
  rendered as `Types.setOf(...)`/`Types.mapOf(...)`; `Parameter`/`FieldInjectionPoint` are unchanged — they already
  carry `java.lang.reflect.Type`, and `Parameter.key()` already builds the right key.
- **Marker reading**: `@IntoSet`/`@IntoMap` read off the bean type or `@Provides` method; new
  `ContributionModel(TypeName elementType, @Nullable String mapKey)` on `BeanModel`/`ProvidesModel`.
- **Validation**: the error table above, emitted through `Diagnostics.error` in the existing validate paths.
- **Generation**: when a contribution is present, `BeanFactoryGenerator`/`ProvidesFactoryGenerator` emit the two
  overrides and skip default exposure; scope, metadata, and injection points generate as today.
- **Planning**: a collection dependency creates no default-factory demand and no cycle edges in v1.

## Testing

- **Unit (`inject`)**: `Types` equality both directions against `TypeLiteral`-captured JDK types, including
  `hashCode` (registry lookups depend on it); set and map grouping; duplicate map key at boot; a condition-filtered
  contributor shrinks the collection; filtering the last contributor makes the key absent; singleton vs prototype
  contributor semantics; injected collections are immutable.
- **Processor golden**: markers generate `contribution()` and empty `exposedKeys()`; each validation errors; a
  `Set<Handler>` site renders `Types.setOf(...)`.
- **End-to-end examples**: classpath example gains `Set<Handler>` and `Map<String, Handler>` beans with one
  contributor gated by `@ConditionalOnProperty`; the module-path example gains a contribution (hand-written
  `module-info` provides clause updated accordingly).

## Decisions log

- Explicit markers over implicit collection (preserves fail-fast duplicate detection; house style).
- `Set` + `Map` only; no `List` (Guice precedent; callers sort).
- Inline String map keys on `@IntoMap` (no key-annotation machinery).
- No iteration-order promise; no `@Priority` (add later if needed, non-breaking).
- Absent collection is an error, not an empty collection (fail-fast over convenience).
- Runtime aggregation in `DefaultContext` (approach A) — generated aggregate factories were rejected because they
  break cross-module merging and conditional filtering.
