# Conditional Beans

## The problem

Some beans should exist only in some deployments — a feature behind a flag, an
integration only configured environments use. Without support for this, the
choice is between shipping separate artifacts per environment or writing
wiring code that consults configuration by hand.

## How it solves it

A bean or `@Provides` method can carry a **conditioning annotation**. When the
injector is created, each factory's conditions are evaluated; an unmatched
condition removes the bean from the graph entirely — no binding, no
[bean definition](injector-injection.md), no
[collection contribution](multibindings.md).

`@ConditionalOnProperty` is built in:

```java
@ConditionalOnProperty(value = "examples.premium", havingValue = "true")
public final class PremiumFeature {
    @Inject
    public PremiumFeature() {}
}
```

```java
// property unset: the bean does not exist
Injector.create().getBeanDefinition(Key.of(PremiumFeature.class)); // Optional.empty()

System.setProperty("examples.premium", "true");
Injector.create().getInstance(PremiumFeature.class); // resolves
```

Its attributes: `value` names the property, `havingValue` is the required
value (empty means "any value"), and `matchIfMissing` makes an absent property
count as a match. Properties are read from system properties.

### Custom conditions

A condition is a pair, mirroring how [scopes](scopes.md) work: an annotation
meta-annotated with `@ConditioningAnnotation`, and a `Condition`
implementation discovered through `ServiceLoader`. The condition receives the
annotation's attribute values and decides `matches`.

## Limitations

- Conditions are evaluated once, at injector creation. A property change
  afterwards has no effect on an existing injector; conditions are for
  deployment-time shape, not runtime toggling.
- There is no fallback mechanism: when a condition removes a bean, nothing
  takes its place, and a consumer that hard-depends on it makes injector
  creation fail with `UnsatisfiedDependencyException`. Consumers must treat the bean as optional —
  via a [collection](multibindings.md) or an
  [injector probe](injector-injection.md).
- A conditioning annotation with no registered `Condition` implementation, or
  with more than one, is an error at injector creation
  (`ConditionResolutionException`), so a jar cannot silently replace a
  condition.
