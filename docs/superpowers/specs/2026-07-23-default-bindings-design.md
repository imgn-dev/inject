# Default bindings — Design

**Date:** 2026-07-23
**Status:** Approved design, pre-implementation

## 1. Overview

A binding may be marked as a **default**: a fallback recipe used only when no other
binding satisfies its key. The consumer side is untouched — injection sites keep
their plain `T` (or `Provider<T>`) declarations and always receive an instance;
absence never reaches user code. This is the framework's answer to keys that may
be unsatisfied at runtime, chiefly a `@ConditionalOnProperty` bean whose condition
fails.

`Optional<T>` and `@Nullable` injection sites were considered and rejected:
`Optional` is documented as a return-type vehicle, not a parameter or field type,
and a null-on-absence contract pushes absence handling into every consumer.
A default binding keeps the guarantee every other binding already gives —
resolution returns a usable instance or fails fast.

## 2. User-facing API

One annotation in `be.imgn.inject`:

```java
/** Marks a {@code @Provides} method as the fallback recipe for its key. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Default {}
```

Usage:

```java
@ConditionalOnProperty("audit.enabled")
public final class DbAuditLog implements AuditLog {
    @Inject
    DbAuditLog(Database db) { ... }
}

public final class AuditConfig {
    @Provides
    @Default
    static AuditLog noopAuditLog() {
        return new NoopAuditLog();
    }
}

// audit.enabled=true  → DbAuditLog
// audit.enabled unset → NoopAuditLog
```

## 3. Semantics

- `@Default` is valid **only on a `@Provides` method** — static or instance host,
  exactly as any other `@Provides` recipe.
- The key is the method's return type plus its qualifying annotation, unchanged.
  A default is per-key: `@Default @Named("db")` falls back only for the
  `@Named("db")` key.
- A scoping annotation on the method behaves as usual — the scope wraps whichever
  provider wins selection.
- A `@ConditionalOnProperty` (or any conditioning annotation) on the default
  composes: a default whose own condition fails is not a candidate.

### Selection

Selection happens once, at `Context` bootstrap, after conditions are evaluated:

1. A non-default binding for the key whose condition passes **wins**; the default
   is discarded for that key.
2. No live non-default binding → the live default is used.
3. Two or more live non-default bindings for one key →
   `DuplicateBindingException`, unchanged.
4. Two or more live defaults for one key → `DuplicateBindingException`. When the
   processor can see both in one compilation, it reports the conflict at compile
   time instead.
5. A key whose only binding is a default with a failing condition is unsatisfied →
   `UnsatisfiedDependencyException` at resolution, unchanged.

Consumers — `@Inject` sites, `Provider<T>` dependencies, multibinding
*consumers* (`Set<T>`/`Map<K, V>` injection) — are oblivious: they see the
winning provider.

## 4. Prohibitions (compile errors)

| Source | Error |
|--------|-------|
| `@Default` on a method without `@Provides` | `@Default requires @Provides` |
| `@Default` with `@IntoSet` or `@IntoMap` | contributions aggregate; a fallback contribution is contradictory |
| `@Default` on an assisted recipe (a `@Provides` method with `@Assisted` parameters) | assisted recipes are not container bindings |

Reported via `Messager` with the offending method attached, like every other
processor-side validation.

## 5. Runtime carrier

- `Factory<T>` gains `public boolean isDefault()` returning `false`; the
  generated factory for a `@Default @Provides` method overrides it to return
  `true`.
- `DefaultContext`, while building the registry, partitions each key's live
  factories into non-defaults and defaults, then applies the selection rules of
  §3. No new exception type; no change to `Injector`, `Key`, or resolution.

## 6. Testing

- **Runtime (JUnit 6 + AssertJ):** default loses to a live conditional binding;
  default wins when the condition fails; duplicate live defaults throw
  `DuplicateBindingException`; a scoped default is wrapped once; default-only key
  with failing condition throws `UnsatisfiedDependencyException`.
- **Processor (`google-compile-testing`):** the prohibition matrix of §4; the
  generated factory overrides `isDefault()`; duplicate defaults visible in one
  compilation fail the build.
- **Examples:** `inject-examples` adds a conditional bean plus its noop default;
  `inject-examples-module` mirrors it on the module path.
