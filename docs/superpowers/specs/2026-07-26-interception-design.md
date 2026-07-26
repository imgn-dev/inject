# Method interception — Design

A method annotated with an intercepting annotation runs inside a chain of `MethodInterceptor` beans. Zero reflection:
the processor emits a subclass of the bean that overrides each intercepted method and dispatches through the chain to
`super`.

## Goals

- An annotation meta-annotated `@InterceptingAnnotation` on a bean class or method routes matching method calls
  through interceptors, with the annotation's compile-time member values available to each interceptor.
- An interceptor is an ordinary bean carrying `@Intercepts(TheAnnotation.class)`, so it gets constructor injection,
  scopes, conditions, and test-time replacement like any other bean — and needs no new `ServiceLoader` service type,
  hence no new `provides` directive on the module path.
- The intercepted bean keeps one identity: the proxy *is* the instance the container hands out, so member injection,
  `@PostConstruct`, scoping, destruction, and self-invocation all behave as if interception were part of the class.
- Every placement a subclass proxy cannot honour is a compile error, never a silent no-op.

Out of scope (v1): constructor and field interception; introduction/mixins (an interceptor cannot add methods);
interception of `@Provides` products and assisted recipes (the container does not construct them, or constructs them
per call outside the registry); inherited methods (consistent with member injection and lifecycle callbacks, which
read declared members only); an explicit ordering attribute (source order decides, and an attribute stays
addable later without breaking).

## User-facing API

Two annotations in `be.imgn.inject`:

```java
/** Meta-annotation marking an annotation as an interception trigger. */
@Retention(RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface InterceptingAnnotation {}

/** Marks a bean as the interceptor handling one intercepting annotation. */
@Retention(RUNTIME)
@Target(ElementType.TYPE)
public @interface Intercepts {
    Class<? extends Annotation> value();
}
```

`@InterceptingAnnotation` is the fifth stereotype, alongside `@QualifyingAnnotation`, `@ScopingAnnotation`,
`@ConditioningAnnotation`, and `@InjectingAnnotation`. `Annotations.isFrameworkRelated` and
`Annotations.stereotypes` in the processor learn it, so an intercepting annotation is captured into
`AnnotationMetadata` as `AnnotationValue` data like every other framework annotation.

Declaring and using an interceptor:

```java
@InterceptingAnnotation
@Retention(RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Timed {
    String value() default "";
}
```

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

```java
public class OrderService {                       // non-final: the proxy subclasses it

    @Inject
    public OrderService(Repo repo) { ... }

    @Timed("place")
    public Order place(Cart cart) { ... }
}
```

The annotation module depends on nothing: `@Timed` can ship in an API jar while `TimingInterceptor` lives with the
metrics implementation.

## SPI (`be.imgn.inject.context`)

```java
public interface MethodInterceptor {
    @Nullable Object invoke(MethodInvocation invocation) throws Throwable;
}

public interface MethodInvocation {
    Object target();
    String methodName();
    Class<?> declaringType();
    Class<?> returnType();
    List<Class<?>> parameterTypes();
    List<@Nullable Object> arguments();                                    // unmodifiable
    AnnotationValue annotation();                                          // the annotation that pulled this
                                                                           // interceptor into the chain
    @Nullable Object proceed() throws Throwable;
    @Nullable Object proceed(@Nullable Object... arguments) throws Throwable;
}

/** The innermost hop of a chain: the call to the bean's own implementation. */
@FunctionalInterface
public interface Joinpoint {
    @Nullable Object invoke(@Nullable Object[] arguments) throws Throwable;
}

/** Immutable call-site descriptor; one static instance per intercepted method. */
public final class InterceptedMethod {
    public static InterceptedMethod of(
            Class<?> declaringType,
            String methodName,
            Class<?> returnType,
            List<Class<?>> parameterTypes,
            List<AnnotationValue> bindings);

    public MethodInterception bind(Context context);
}

/** A context-bound chain; one instance per proxy instance per intercepted method. */
public final class MethodInterception {
    public @Nullable Object invoke(Object target, @Nullable Object[] arguments, Joinpoint joinpoint) throws Throwable;
}
```

`MethodInvocation` exposes no `java.lang.reflect.Method`: every member is a compile-time constant baked into the
proxy. `arguments()` is unmodifiable; substitution goes through `proceed(Object...)`, which validates arity and
assignability (primitives against their wrappers) and throws `IllegalArgumentException` naming the offending
position.

Two exceptions in `be.imgn.inject`, both extending `InjectionException`:

```java
public class InterceptionException extends InjectionException {}           // undeclared throwable escaped a chain
public class InterceptorResolutionException extends InjectionException {}  // no @Intercepts bean for an annotation
```

## Semantics

**Placement.** A class-level annotation sweeps every declared method that is not `private`, `static`, or `final`,
excluding `Object` methods and `@Inject`/`@PostConstruct`/`@PreDestroy` methods. A method-level annotation applies to
that method alone. Inherited methods are never swept.

**Order.** Interceptors run in source order, first-listed outermost; class-level annotations wrap method-level ones,
because the broader declaration sits outside.

```java
@Retry
public class OrderService {

    @Secured
    @Timed
    public Order place(Cart cart) { ... }
}
```

produces `Retry → Secured → Timed → place`.

**Same annotation at both levels.** The method-level declaration replaces the class-level one, so a method can retune
`@Timed("checkout")` without also inheriting `@Timed("")`.

**Self-invocation is intercepted.** The proxy is the instance, so a call from one bean method to another dispatches
virtually into the override.

**Calls made from the bean's own constructor are not intercepted.** The chain fields are assigned after `super(…)`
returns, and Java 21 has no statements before `super`. The generated override null-checks its chain field and falls
through to `super` when it is unset, making the rule deterministic instead of an NPE.

**Providers are resolved when the proxy is constructed; interceptor instantiation waits for the first intercepted
call.** `bind` resolves `Provider`s only — a missing interceptor therefore fails at bean construction, while an
interceptor may depend on beans that depend on intercepted beans without tripping the cycle guard. Each call does
`provider.get()`, so the interceptor's own scope decides sharing: a `@Singleton` interceptor is shared, a prototype
interceptor is fresh per call.

**Exposure is unaffected.** `Factory.type()` stays the bean type, so exposed keys, `@ExposeAs`, qualifiers,
collections, scopes, the cycle guard, destruction tracking, member injection, and `@PostConstruct` all operate on the
proxy instance unchanged.

**`MethodInterceptor` joins `EXCLUDED_DEFAULT_EXPOSURES`.** Without it, two interceptor beans would collide on the
`MethodInterceptor` key through default supertype exposure.

**Builder overrides bypass interception** for the intercepted bean itself: an override binds a raw instance or
provider, and nothing proxies it. Overriding an *interceptor* bean is different: after overrides merge into the
registry, `DefaultContext` checks each interceptor entry's bean key (`Key.of(factory.type())`) against the overrides
map and swaps in the override's provider when present, so the override reaches the chain too, not just direct
resolution.

**Child injectors** resolve intercepted beans through the parent's factories, so interception is intact;
`ChildContext.interceptor` delegates to the parent unconditionally — it does not consult the child's own bindings. A
child binding for an interceptor's own key therefore shadows direct resolution through the child but does not reach
any chain; the override-reaches-the-chain behaviour above is a root-builder-only mechanic.

## Compile-time errors

| Situation | Message fragment |
|---|---|
| Intercepting annotation on a `final` class | cannot be intercepted: the generated proxy must subclass it. Remove `final` |
| Intercepting annotation on a `record` | records are implicitly final and their accessors are part of their identity |
| Intercepting annotation on an `enum` | enum constants are created by the JVM, not by a factory |
| Intercepting annotation on a `sealed` class | `sealed` permits only its listed subclasses |
| Intercepting annotation on a generic class | a generic class is bound under its raw type |
| Method-level annotation on a `final`, `static`, or `private` method | names the offending modifier |
| Method-level annotation on a method whose `throws` clause holds a type variable | a type variable cannot be a `catch` parameter |
| Method-level annotation on an `@Inject`, `@PostConstruct`, or `@PreDestroy` method | framework plumbing cannot be intercepted |
| Intercepting annotation on a `@Provides` method | the container does not construct the result |
| Intercepting annotation on an assisted recipe | assisted instances are built per call, outside the registry |
| Bean carrying `@Intercepts` that also carries an intercepting annotation | an interceptor cannot intercept itself |
| `@Intercepts` on a class that does not implement `MethodInterceptor` | must implement `MethodInterceptor` |
| Class-level annotation with zero eligible methods | the annotation has no effect |

A class-level annotation over a bean that has *some* ineligible methods skips them silently: `final` accessors and
`Object` methods must not make class-level interception unusable.

An intercepting annotation with no `@Intercepts` bean is not a compile error — the interceptor may live in another
artifact. It fails when the bean is constructed — the proxy binds its whole chain in its constructor — with
`InterceptorResolutionException`, naming the annotation and the intercepted method (its declaring type and name), with
the actionable `@Intercepts(TheAnnotation.class)` hint kept alongside.

## Runtime model

`Factory` gains one override, mirroring `scope()` and `contribution()`:

```java
protected Optional<Class<? extends Annotation>> intercepts() {
    return Optional.empty();
}
```

`DefaultContext` builds an annotation-type → provider index from the factories that report one, and `Context` gains:

```java
Provider<MethodInterceptor> interceptor(Class<? extends Annotation> annotationType);
```

The index stores the fully wrapped provider the context already built for that factory, not a key lookup, so an
interceptor bean carrying `@ExposeAs` (or exposing no key of its own) is still reachable as an interceptor. Two beans
declaring `@Intercepts` for the same annotation are a `DuplicateBindingException` at injector creation, naming the
annotation and both beans — the same rule as two bindings for one key.

`InterceptedMethod.bind(context)` resolves one `Provider` per binding, in order, at proxy construction.
`MethodInterception.invoke` allocates one short-lived `Invocation` per chain position, carrying that position and the
arguments; chain state is therefore per call, so recursion and concurrent calls on one proxy are safe. Generated
joinpoint lambdas are hoisted to instance fields, so an intercepted call costs one `Object[]` plus one `Invocation`
per chain position.

## Generated code

One new artifact per intercepted bean, `$Bean$$Intercepted`, in the bean's package, following the existing naming
rule (`$$Factory`, `$$Impl`, `$$Scope`):

```java
public final class $OrderService$$Intercepted extends OrderService {

    private static final InterceptedMethod PLACE = InterceptedMethod.of(
            OrderService.class, "place", Order.class, List.of(Cart.class),
            List.of(AnnotationValue.of(Timed.class, Map.of("value", "place"),
                    Set.of(InterceptingAnnotation.class))));

    private final @Nullable MethodInterception place$0;
    private final Joinpoint place$0$joinpoint = args -> super.place((Cart) args[0]);

    $OrderService$$Intercepted(Context $context, Repo repo) {
        super(repo);
        this.place$0 = PLACE.bind($context);
    }

    @Override
    public Order place(Cart cart) {
        MethodInterception interception = place$0;
        if (interception == null) {
            return super.place(cart);
        }
        try {
            return (Order) interception.invoke(this, new Object[] {cart}, place$0$joinpoint);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new InterceptionException(
                    "Interceptor threw an undeclared exception from OrderService.place", t);
        }
    }
}
```

The proxy constructor mirrors the bean's `@Inject` constructor signature, prefixed with a `Context` parameter named
`$context` — `$`-prefixed like the generator's other locals (`$interception`, `$e`, `$t`, `$arguments`, `$joinpoint`)
so it cannot collide with a bean constructor parameter of the same name; a bean without one gets a `Context`-only
constructor calling `super()`. The generated factory changes in one place:

```java
@Override
protected OrderService createInstance(Context context) {
    return new $OrderService$$Intercepted(context, getInstanceForConstructor(0, context));
}
```

Shape rules for overrides:

- **Declared checked exceptions pass through** — the override carries the bean method's `throws` clause, so each is
  caught and rethrown before the `Throwable` catch that wraps.
- **`void`** — the joinpoint returns `null` and the override discards the result.
- **Primitive returns** — a null check precedes unboxing, throwing `InterceptionException` naming the method and the
  expected type rather than an opaque NPE from the cast.
- **Varargs and type parameters** are mirrored on the proxy and the override; a generic return forces
  `@SuppressWarnings("unchecked")` on the generated method.

## Processor changes

- `Annotations` — `InterceptingAnnotation` added to `isFrameworkRelated` and `stereotypes`.
- `GatherStage` — collects intercepted methods per bean and `@Intercepts` beans.
- `BeanModel` — gains `interception()`: per method the name, return type, parameter types and names, `throws` list,
  varargs flag, and the ordered bindings.
- `PlanStage` — the compile-time error table.
- `InterceptedProxyGenerator` — new; emits `$Bean$$Intercepted`.
- `BeanFactoryGenerator` — `createInstance` targets the proxy; emits the `intercepts()` override for interceptor
  beans.
- `ModelFactory` — `MethodInterceptor` added to `EXCLUDED_DEFAULT_EXPOSURES`.

## Testing

- **Runtime (`inject`)**: source order and class-wraps-method composition; `proceed(Object...)` substitution plus
  arity and type rejection; declared checked exception passes through while an undeclared one becomes
  `InterceptionException`; primitive and `void` returns, including the null-for-primitive message; missing
  interceptor throws `InterceptorResolutionException`; an interceptor receives its own injections; singleton
  interceptor shared versus prototype fresh per call; self-invocation intercepted; a call from the bean's
  constructor not intercepted; concurrent calls on one proxy.
- **Processor golden**: one case per error-table row, including `record`, `enum`, and `sealed`; generated-source
  assertions for a class-level sweep, a stacked-order method, and varargs, primitive, and `void` shapes.
- **End-to-end examples**: `inject-examples` gets `@Timed` and `@Audited` on one bean demonstrating order, with an
  interceptor holding an injected collaborator; `inject-examples-module` gets one interceptor, proving no new
  `provides` directive is required.
- **Docs**: new `docs/features/interception.md` plus its README entry; `docs/features/type-exposure.md` updated,
  since its excluded-contracts list is user-visible and `MethodInterceptor` joins it.

## Decisions log

- Subclass proxy over a delegating wrapper: one identity, self-invocation intercepted, member injection and
  `@PostConstruct` unchanged, and no split between the bean and what the container hands out. The cost — the bean
  must be subclassable — is paid as a compile error with a fixable message.
- Interceptors are beans, not a `ServiceLoader` SPI: they get injection, scopes, conditions, and test overrides for
  free, and the module path gains no new `provides` directive. The pairing lives on the interceptor
  (`@Intercepts`), not on the annotation, so an annotation in an API jar drags no implementation with it.
- Source order over an ordering attribute: deterministic, zero API surface, reads like the call stack it produces.
  An `order` attribute remains addable later.
- Immutable `MethodInvocation` with `proceed(Object...)` over a live mutable argument array: same power, no aliasing
  footgun, and substitution is validated where it happens.
- Constructor-time calls fall through to `super` rather than NPE-ing on an unset chain — the only honest option
  while Java 21 forbids statements before `super`.
