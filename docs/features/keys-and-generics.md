# Keys and Generic Types

## The problem

"Which binding?" needs a precise address. A `Class` literal cannot carry a
[qualifier](qualifiers.md), and Java erasure means it cannot express a
parameterized type either — `Set<Notifier>` and `Set<Formatter>` are both just
`Set.class`.

## How it solves it

`Key<T>` is the binding address used everywhere in the framework: a
`java.lang.reflect.Type` plus an optional qualifier. Identity covers both, so
`@Named("db") String` and `@Named("cache") String` are distinct keys.

```java
Key<Registry> plain = Key.of(Registry.class);
Key<String> qualified = Key.of(String.class, Names.named("greeting"));

String greeting = injector.getInstance(qualified);
```

For parameterized types, `TypeLiteral` captures the full generic type through
an anonymous subclass, keeping the type argument the compiler checked:

```java
Set<Notifier> notifiers = injector.getInstance(new TypeLiteral<Set<Notifier>>() {});
```

`Types` builds the same parameterized types programmatically — handy when the
element type is a variable, and guaranteed to produce keys equal to their
`TypeLiteral` counterparts:

```java
Key<?> setKey = Key.of(Types.setOf(Notifier.class));
Key<?> mapKey = Key.of(Types.mapOf(String.class, Formatter.class));
```

These are exactly the keys under which [multibindings](multibindings.md)
register their collections, so programmatic lookup of a contributed `Set` or
`Map` uses the same address the generated code does.

`Injector` accepts all three currencies — `Class`, `Key`, `TypeLiteral` — on
`getInstance` and `getProvider`.

## Limitations

- Keys name bindings; they do not create them. A parameterized key resolves
  only if something bound that exact type — there is no automatic binding for
  arbitrary generic types.
- Matching is exact: no variance, no wildcard matching, no raw-type fallback.
- A `TypeLiteral` must be an anonymous subclass with an explicit type argument
  (`new TypeLiteral<Set<Notifier>>() {}`); a type variable at that position
  defeats capture.
