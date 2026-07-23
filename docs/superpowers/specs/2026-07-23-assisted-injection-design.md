# Assisted injection — Design

Build beans from a mix of container-resolved dependencies and caller-supplied arguments, through user-declared
factory interfaces whose implementations the processor generates.

## Goals

- A bean whose `@Inject` constructor has `@Assisted` parameters is created only through a factory: container
  dependencies resolve as usual, assisted parameters come from the factory-method call.
- A `@Provides` method with `@Assisted` parameters is likewise a factory-only recipe (assisted construction of types
  the user does not own).
- Factories are ordinary interfaces the user writes; the processor generates and binds the implementation. Zero
  reflection, same ServiceLoader path as every other binding.

Out of scope (v1): assisted parameters on `@Inject` methods or fields; factory methods returning `Provider`;
assisted beans in collections.

## User-facing API

Two annotations in `be.imgn.inject`:

```java
/** Marks a caller-supplied parameter; value disambiguates same-typed parameters. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Assisted {
    String value() default "";
}

/** Marks a factory-interface method the processor implements. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AssistedFactory {
    /** The implementation class, or the recipe's declaring class when {@link #method()} is set. */
    Class<?> value() default void.class;

    /** The name of an assisted {@code @Provides} method on {@link #value()}. */
    String method() default "";
}
```

Usage — constructor recipes:

```java
public final class PdfReport implements Report {
    @Inject
    public PdfReport(Database db, @Assisted String id, @Assisted("footer") String footer) { ... }
}

public interface ReportFactory {
    @AssistedFactory                        // no value: the return type IS the implementation
    PdfReport create(String id, @Assisted("footer") String footer);

    @AssistedFactory(CsvReport.class)       // value: implementation link; return any supertype of it
    Report createCsv(String id);

    default Report createDefault() { return create("d", ""); }   // default methods are untouched
}

@Inject
public Reports(ReportFactory factory) { ... }   // the factory is an ordinary injectable bean
```

Usage — `@Provides` recipes (third-party types):

```java
public class Configs {
    @Provides
    static LegacyReport legacy(Database db, @Assisted String id) {   // assisted: binds nothing itself
        return new LegacyReport(db, id);
    }
}

public interface LegacyReportFactory {
    @AssistedFactory(value = Configs.class, method = "legacy")
    LegacyReport create(String id);
}
```

## Semantics

- **Merged model**: any `@Assisted` parameter on an `@Inject` constructor makes the bean assisted — it loses its
  own key entirely and is reachable only through factories. Fresh instance per factory call. The same rule applies
  to a `@Provides` method with `@Assisted` parameters: it binds nothing; factories name it via `method()`.
- `@Inject` fields and methods on an assisted bean inject normally during each factory call (construct → methods →
  fields, the standard order).
- An interface with at least one `@AssistedFactory` method is a generated, bound factory bean (its interface type is
  the key; prototype scope; conditions on the interface gate the binding via existing metadata machinery). Several
  factory interfaces may target the same recipe.
- Matching is bijective per factory method: its parameters ↔ the recipe's `@Assisted` parameters, by type plus
  `@Assisted` tag; same-typed parameters need tags on both sides (tags on factory-method parameters are declared
  with `@Assisted("tag")`).
- Link resolution:
  - no members → the factory method's return type is the implementation class (exact);
  - `value()` alone → implementation class; the return type must be a supertype of it (assignability validated);
  - `value()` + `method()` → the named method on `value()` must be exactly one `@Provides` method with `@Assisted`
    parameters; the recipe's return type must be assignable to the factory method's return type. Static and
    instance recipes both work (an instance recipe resolves its host per call, like `@Provides` today).
- Cross-module works: links are explicit class (+ method) references, resolvable through `javax.lang.model` from
  dependency jars; no search, no ambiguity.
- Hand-written factories remain the escape hatch: an interface without `@AssistedFactory` methods is an ordinary
  type — provide an implementation yourself via a normal bean or `@Provides`. Providing an interface that *does*
  carry `@AssistedFactory` methods collides with the generated binding: existing `DuplicateBindingException`.

## Compile-time errors

| Case | Diagnostic contains |
|---|---|
| Injecting an assisted bean directly (visible in-compilation) | "only available through an @AssistedFactory" |
| Scope, qualifier, `@ExposeAs`, `@IntoSet`/`@IntoMap` on an assisted bean or assisted `@Provides` method | "assisted" + "cannot" |
| `@Assisted` on an `@Inject` method parameter | "misplaced" |
| Factory method ↔ recipe parameter mismatch (missing, extra, tag, type) | "does not match" |
| `@AssistedFactory` whose return type is not assignable from the implementation / recipe return | "not assignable" |
| `method()` set with default `value()` | "requires the declaring class" |
| `method()` naming zero or several assisted `@Provides` methods | "no assisted"/"ambiguous" |
| `value()` class having no assisted `@Inject` constructor (when `method()` empty) | "no assisted constructor" |
| Abstract non-`@AssistedFactory` method in an interface that has `@AssistedFactory` methods | "cannot be implemented" |
| `@AssistedFactory` on a class method or on a type | "interface method" |
| Conditioning annotation on an assisted recipe | "factory interface" |

Cross-module direct injection of an assisted bean is invisible at compile time and fails at runtime as an ordinary
missing binding (the key never exists).

## Runtime model

No runtime API changes. For each factory interface the processor generates:

- an implementation class (bean's package) holding the `Context`, each `@AssistedFactory` method constructing the
  implementation (or invoking the recipe) with container parameters resolved through the context and assisted
  parameters passed through, then performing the bean's method/field injection;
- a `Factory<TheInterface>` registering the implementation under the interface key, ServiceLoader-registered like
  every generated factory.

## Testing

- **Runtime-through-processor (examples)**: constructor recipe with mixed params and a tag; supertype return via
  `value()`; `@Provides` recipe (static and instance); factory injected and called twice yields distinct bean
  instances; container dependency of an assisted bean respects its own scope across calls.
- **Processor golden**: generated impl + factory registration; each error-table row errors with its fragment;
  assisted bean generates no own-key factory; hand-provided annotated interface collides.
- **Module path**: one assisted factory in the module example with the provides clause updated.

## Decisions log

- Merged model (`@Inject` + `@Assisted`) over a separate `@AssistedInject`: one annotation less; compile-time
  processing makes the split unnecessary; chosen with eyes open to the class-contract flip.
- Method-level `@AssistedFactory` over type-level: multi-method factories, per-method impl links, no
  one-abstract-method rule.
- Explicit impl/recipe links over `@ExposeAs`-based or search-based resolution: declaration sits at the generation
  site, works cross-module, no ambiguity.
- Assisted `@Provides` included (user decision), linked by `value()` + `method()`.
- Hand-written factories deliberately unannotated — the escape hatch needs no framework support.
