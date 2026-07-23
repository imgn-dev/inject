# Multibindings

## The problem

Plugin-style designs need "all implementations of X", assembled without a
central class that lists them. With one-key-one-binding, a second `Notifier`
is a duplicate, and something must still know every implementation to collect
them — the coupling just moves.

## How it solves it

A bean or `@Provides` method marks itself as a **contribution** to a
collection. Contributions from the whole classpath/module path are aggregated
into a single injectable collection; adding an element means adding one class,
touching nothing else.

`@IntoSet(E.class)` contributes to `Set<E>`:

```java
@IntoSet(Notifier.class)
public final class EmailNotifier implements Notifier {
    @Inject
    public EmailNotifier() {}
}

@IntoSet(Notifier.class)
public final class SmsNotifier implements Notifier {
    @Inject
    public SmsNotifier() {}
}
```

`@IntoMap(value = E.class, key = "...")` contributes to `Map<String, E>`:

```java
@IntoMap(value = Formatter.class, key = "json")
public final class JsonFormatter implements Formatter {
    @Inject
    public JsonFormatter() {}
}
```

Consumers inject the collection like any other dependency:

```java
public final class NotificationCenter {
    @Inject
    public NotificationCenter(Set<Notifier> notifiers, Map<String, Formatter> formatters) { ... }
}
```

Contributions compose with the rest of the framework: each element keeps its
own [scope](scopes.md), and a [conditional](conditional-beans.md) contribution
simply drops out of the collection when its condition does not match:

```java
@IntoSet(Notifier.class)
@ConditionalOnProperty(value = "examples.push", havingValue = "true")
public final class PushNotifier implements Notifier {
    @Inject
    public PushNotifier() {}
}
```

The collection itself is rebuilt per resolution (an unmodifiable snapshot);
whether elements are shared across snapshots is each element's scope's
decision. The processor verifies at compile time that every contribution is
assignable to its declared element type.

## Limitations

- Collection shapes are exactly `Set<E>` and `Map<String, E>`. Map keys are
  `String` only.
- Collections are unqualified — a qualifier on a contribution is a compile
  error — so there is one collection per element type.
- No ordering: `Set` iteration order is unspecified, and there is no priority
  attribute.
- A collection with zero live contributions is not an empty collection — the
  key is simply unbound, and injecting it fails with
  `UnsatisfiedDependencyException`. At least one contribution must match.
- Duplicate `@IntoMap` keys are rejected at compile time within one
  compilation; across separately compiled artifacts they surface at injector
  creation.
- A contribution is reachable only through its collection: it cannot combine
  with `@ExposeAs`, and it is not injectable standalone.
