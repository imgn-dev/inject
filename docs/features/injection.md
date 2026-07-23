# Zero-Reflection Injection

## The problem

Wiring an object graph by hand means every constructor call site repeats the
same assembly code, and every new dependency ripples through all of them.
Automating that wiring usually costs something at runtime: scanning classes,
inspecting members, bypassing visibility. Those costs grow with the graph and
resist ahead-of-time analysis.

## How it solves it

All wiring code is generated at compile time. An annotation processor emits one
`Factory` subclass per injectable class — plain Java source, compiled along with
the rest of the build. At runtime the framework only calls those generated
factories; it never inspects a class.

A class opts in by annotating its injection points with `@Inject`:

- **Constructor** — exactly one constructor carries `@Inject`; its parameters
  are resolved and passed in.
- **Methods** — after construction, each `@Inject` instance method is called
  with resolved arguments.
- **Fields** — each `@Inject` field is assigned a resolved value.

```java
public final class GreetingService {
    private final Registry registry;

    @Inject
    Clock clock; // field injection

    @Inject
    public GreetingService(Registry registry) { // constructor injection
        this.registry = registry;
    }

    @Inject
    void connect(Notifier notifier) { // method injection
        // called after construction with a resolved Notifier
    }
}
```

The generated factory is emitted **into the bean's own package**, so it can
legally call package-private constructors, methods, and fields. Visibility is
enforced by the Java compiler at build time, not worked around at runtime.

Generated factories are discovered through `ServiceLoader`. Creating an
injector loads them and builds the binding registry once; the registry is
immutable afterwards and safe for concurrent use:

```java
Injector injector = Injector.create();
GreetingService service = injector.getInstance(GreetingService.class);
```

Because wiring is ordinary generated code, mistakes the processor can see —
a missing `@Inject` constructor, a marker misused — are reported as compile
errors, with no cost deferred to runtime.

## Limitations

- `private` members cannot be injected: the generated factory is a separate
  class in the same package, so it reaches package-private members but not
  private ones.
- New instances are created per resolution by default (see
  [scopes](scopes.md)); nothing is cached unless a scope says so.
- The graph is assembled from whatever factories `ServiceLoader` finds at
  runtime. A dependency missing from that set is reported on first resolution
  of the bean that needs it (`UnsatisfiedDependencyException`), not at injector
  creation.
- Classes without an `@Inject` constructor are invisible to the processor; types
  you do not control are supplied through
  [`@Provides` methods](provides-methods.md).
