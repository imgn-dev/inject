# Qualifiers

## The problem

A type alone is often not a sufficient address. Two `String` values — a
greeting and a database URL — share a type but must never be confused. Binding
by type only would make the second `String` a duplicate.

## How it solves it

A binding key is a **type plus an optional qualifier annotation**. Qualifier
identity is the annotation type *and its attribute values*: `@Named("db")` and
`@Named("cache")` are two distinct keys; a marker qualifier with no attributes
matches by type alone.

`@Named` is the built-in string qualifier:

```java
public final class Config {
    @Provides
    @Named("greeting")
    static String greeting() {
        return "Hello";
    }
}

public final class GreetingService {
    @Inject
    public GreetingService(@Named("greeting") String greeting) { ... }
}
```

Custom qualifiers are ordinary annotations meta-annotated with
`@QualifyingAnnotation`. Attributes participate in identity automatically:

```java
@QualifyingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
public @interface Flavor {
    String value();
}
```

For programmatic lookups, `Key` carries the qualifier and `Names.named` builds
the `@Named` instance:

```java
String greeting = injector.getInstance(Key.of(String.class, Names.named("greeting")));
```

## Limitations

- One qualifier per binding or injection point; qualifiers do not stack.
- [Collection bindings](multibindings.md) are unqualified — a qualifier on an
  `@IntoSet`/`@IntoMap` contribution is a compile error, so there can be only
  one collection per element type.
- Qualifier matching is exact. There is no wildcard, no "any `@Named`", and no
  fallback from a qualified request to an unqualified binding.
