# Method Interception

## The problem

Timing, auditing, retries, and transactions are the same few lines wrapped
around many methods. Written by hand they are copied into every method that
needs them; written with a reflective proxy they cost the zero-reflection
guarantee.

## How it solves it

An annotation meta-annotated `@InterceptingAnnotation` marks calls to route
through interceptors. The processor emits a subclass of the bean that
overrides each intercepted method and dispatches through a chain to `super`:

```java
@InterceptingAnnotation
@Retention(RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Timed {
    String value() default "";
}
```

The interceptor is an ordinary bean marked `@Intercepts`, so it is injected,
scoped, and conditioned like any other:

```java
@Singleton
@Intercepts(Timed.class)
public final class TimingInterceptor implements MethodInterceptor {

    private final Metrics metrics;

    @Inject
    public TimingInterceptor(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            metrics.record(invocation.annotation().stringValue("value").orElse(invocation.methodName()),
                    System.nanoTime() - start);
        }
    }
}
```

The bean itself changes only by being subclassable:

```java
public class OrderService {

    @Inject
    public OrderService(Repo repo) { ... }

    @Timed("place")
    public Order place(Cart cart) { ... }
}
```

Because the generated proxy *is* the instance the injector hands out, member
injection, `@PostConstruct`, scoping, and destruction are unaffected, and a
call from one bean method to another is intercepted too.

### Placement and order

A class-level annotation sweeps every declared method that is not `private`,
`static`, or `final`, skipping `Object` methods and `@Inject` /
`@PostConstruct` / `@PreDestroy` methods. A method-level annotation applies to
that method alone.

Interceptors run in source order, first listed outermost, and class-level
annotations wrap method-level ones:

```java
@Retry
public class OrderService {

    @Secured
    @Timed
    public Order place(Cart cart) { ... }
}
```

runs `Retry → Secured → Timed → place`. Declaring the same annotation at both
levels replaces the class-level one in place, so a method can retune
`@Timed("checkout")` without inheriting `@Timed("")` as well.

### Reading and substituting arguments

`MethodInvocation` exposes the call site as compile-time constants — no
`java.lang.reflect.Method` anywhere — and `proceed(Object...)` substitutes
arguments for the rest of the chain, validating count and types:

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
    String raw = (String) invocation.arguments().get(0);
    return invocation.proceed(raw.strip());
}
```

## Limitations

- The bean must be subclassable. A `final` class, a `record`, an `enum`, a
  `sealed` class, and a generic class are each a compile error naming the
  reason; so is a `final`, `static`, or `private` method carrying the
  annotation.
- A method whose `throws` clause contains a type variable (for example
  `<E extends Exception> void run() throws E`) cannot be intercepted — a type
  variable cannot be a `catch` parameter. A class-level sweep skips such a
  method silently; annotating it directly is a compile error.
- An intercepting annotation is honoured on types and methods only: placing
  one on a field or a parameter has no effect.
- Only method calls are intercepted: no constructor or field interception, and
  an interceptor cannot add methods a bean does not declare.
- Calls made from the bean's own constructor are not intercepted — the chain
  is assigned after `super(...)` returns, so a constructor-time call falls
  through to the bean's own implementation.
- Inherited methods are not swept; a class-level annotation covers declared
  methods only, consistent with
  [member injection](injection.md) and [lifecycle callbacks](lifecycle-callbacks.md).
- [`@Provides`](provides-methods.md) products and
  [assisted](assisted-injection.md) beans cannot be intercepted: the container
  does not construct them.
- [Builder overrides](builder-overrides.md) bypass interception entirely — an
  override binds a raw instance, and nothing proxies it.
- There is no ordering attribute: the bean's author fixes the order by writing
  the annotations in the order they should run.
- An intercepting annotation with no `@Intercepts` bean fails when the bean is
  constructed, with `InterceptorResolutionException`, not at compile time and
  not deferred until an intercepted method is actually called — the chain is
  bound in the generated proxy's constructor. The interceptor may legitimately
  live in another artifact.
