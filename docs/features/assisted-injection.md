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

## Limitations

- `@Assisted` is valid on constructor and `@Provides` parameters only — not on
  `@Inject` methods or fields.
- A factory method returns the built type directly; it cannot return a
  [`Provider`](provider-injection.md) of it.
- Assisted beans are effectively prototype: every factory call constructs; the
  built instances themselves are not scoped or cached.
- The factory is the only door: a type with assisted parameters is not
  resolvable directly through the injector, since the container cannot supply
  the assisted values.
