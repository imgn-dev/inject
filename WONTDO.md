# Won't do

Features that were considered for inject and rejected, with the reason and what to use instead.

## Optional and nullable injection sites

An injection site declared `Optional<T>`, or `@Nullable T`, that receives an empty optional or null when nothing binds
`T`, such as a `@ConditionalOnProperty` bean whose condition fails.

`Optional` is meant as a return type, not as a parameter or field type, and a null-on-absence contract pushes the
handling of absence into every consumer. Every binding guarantees that resolution returns a usable instance or fails
fast; such sites would break that guarantee.

Instead, mark a `@Provides` method `@Default`: it supplies the key only when no other binding does, so the injection
site keeps its plain `T` and always receives an instance.

```java
@Provides
@Default
static PaymentGateway noPayments() {
    return PaymentGateway.DISABLED;
}
```

## List multibindings

A `List<T>` aggregated from contributions, like the `Set<T>` of `@IntoSet`.

Contributions come from independent factories found by `ServiceLoader`, so there is no order a list could keep.

Instead, contribute with `@IntoSet` and sort the injected `Set<T>` where an order matters.
