# Injector Injection and Introspection

## The problem

Most beans should name their dependencies statically. A few legitimately
cannot: a dispatcher that picks a bean by runtime data, infrastructure that
enumerates what the graph contains, code that must handle a bean that may not
exist in this deployment (see [conditional beans](conditional-beans.md)).

## How it solves it

The `Injector` is itself injectable. Every injector resolves
`Key.of(Injector.class)` to itself — in a [child](child-injectors.md), to that
child — with no registration required:

```java
public final class BeanLocator {
    private final Injector injector;

    @Inject
    public BeanLocator(Injector injector) {
        this.injector = injector;
    }

    public Registry locateRegistry() {
        return injector.getInstance(Registry.class);
    }
}
```

For decisions that need "does this binding exist?" or "what is in the graph?",
the injector exposes read-only introspection:

```java
// probe: present only when the bean is in this deployment's graph
Optional<BeanDefinition> premium = injector.getBeanDefinition(Key.of(PremiumFeature.class));
premium.ifPresent(definition -> use(injector.getInstance(PremiumFeature.class)));

// enumerate: all beans carrying a scoping stereotype
List<BeanDefinition> scoped = injector.getBeanDefinitionsByStereotype(ScopingAnnotation.class);
```

A `BeanDefinition` describes a bean without instantiating it: its key, its
annotation metadata (queried by annotation type or by stereotype — the
meta-annotation a marker carries), and its injection points. This is the
supported way to consume a bean that a condition may have removed: probe
first, resolve only when present.

## Limitations

- Injector injection is a service-locator escape hatch. A bean using it hides
  its real dependencies from its constructor signature and cannot be built
  without an injector; prefer declared dependencies wherever the set is known
  statically.
- Introspection is read-only. Definitions cannot be registered, altered, or
  removed through it — the registry is immutable after creation.
- Framework-provided bindings (the injector's self-binding) and
  [builder overrides](builder-overrides.md) carry no `BeanDefinition`;
  introspection covers beans that came from generated factories.
