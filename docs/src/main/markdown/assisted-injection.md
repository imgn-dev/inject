# Assisted Injection

## The problem

Some objects mix two kinds of inputs: dependencies the container knows
(services, configuration) and values only the caller knows at the moment of
creation (the current user, a filename). Neither side can build the object
alone — the caller would have to fetch container services by hand, and the
container cannot invent caller data.

## How it solves it

The constructor marks caller-supplied parameters with `@Assisted`; container
parameters stay unmarked. A factory interface declares a creation method whose
parameters are exactly the assisted ones. The framework generates the
implementation: container parameters resolved from the graph, assisted
parameters forwarded from the call.

```java
public final class Badge {
    @Inject
    public Badge(Registry registry, @Assisted String owner, @Assisted("role") String role) {
        // registry comes from the container; owner and role from the caller
    }
}

public interface BadgeFactory {
    @AssistedFactory
    Badge create(String owner, @Assisted("role") String role);
}
```

```java
BadgeFactory badges = injector.getInstance(BadgeFactory.class);
Badge badge = badges.create("olivier", "admin");
```

Factory parameters and assisted constructor parameters are matched into a
bijection — by type, or by the `@Assisted("name")` value when several assisted
parameters share a type. Mismatches (a parameter left over on either side, an
ambiguous pairing) are compile errors.

Each factory call builds a fresh instance; container-side dependencies still
honor their [scopes](scopes.md), so a `@Singleton` dependency is shared across
all created instances.

### Scoped products

A recipe with a [child-local scope](child-injectors.md#child-local-scopes) —
declared on the class, inherited from a supertype, or on the `@Provides`
method — makes the factory create its product in the nearest injector of the
chain that hosts that scope, and cache it there:

```java
@ViewScoped
public interface DialogView {}

public final class RenameDialog implements DialogView { // @ViewScoped
    @Inject
    public RenameDialog(@Assisted String current, RenameDialogCommands commands) { ... }
}

@ViewScoped
public final class RenameDialogCommands {
    @Inject
    public RenameDialogCommands(Provider<RenameDialog> dialog) { ... }
}
```

```java
Injector view = window.scopedChildBuilder(ViewScoped.class).build();
RenameDialog dialog = view.getInstance(RenameDialogFactory.class).create("draft");
view.getInstance(RenameDialog.class) == dialog; // true
```

The product is built with dependencies from its host. Once created, it
resolves by its key (the recipe's class, or the `@Provides` method's return
type) through the host and its descendants, a `Provider` included, like any
bean of the scope. Before that, resolving it fails when `get()` is called, not
when the `Provider` is injected, so `RenameDialogCommands` above is built while
the dialog is. A second call through the same host fails: the product exists
once per host. Its `@PreDestroy` runs when its host closes. Creating it through
an injector with no host of its scope fails with a
`ScopeResolutionException`, and a product whose scope has a `Scope` service,
such as `@Singleton`, fails when the injector is created.

### `@Provides` recipes

The recipe can also be a `@Provides` method, for types whose construction
needs logic or that cannot carry annotations themselves. The factory then
names its host class and method:

```java
public class StampConfig {
    @Provides
    public static LegacyStamp stamp(@Named("greeting") String greeting, @Assisted String suffix) {
        return new LegacyStamp(greeting + " " + suffix);
    }
}

public interface StampFactory {
    @AssistedFactory(value = StampConfig.class, method = "stamp")
    LegacyStamp create(String suffix);
}
```

## Generated types

A factory and its product can reach the processor in different rounds of one
compilation: the product's constructor may name a type another processor
generates, or another processor may generate the factory interface itself.
The processor keeps the recipes of earlier rounds, and a factory waits for a
product of the same compilation until its recipe is known. A product that
still has no assisted constructor after the last round is an error.

## Limitations

- `@Assisted` is valid on constructor and `@Provides` parameters only — not on
  `@Inject` methods or fields.
- A factory method returns the built type directly; it cannot return a
  [`Provider`](provider-injection.md) of it.
- Assisted beans without a child-local scope are effectively prototype: every
  factory call constructs, and the built instances are not cached.
- The factory is the only door: a type with assisted parameters is not
  resolvable directly through the injector, since the container cannot supply
  the assisted values — except a scoped product, once its host created it.
