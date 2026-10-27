# Construction listeners

## The problem

A framework built on the container often needs to act on every instance of
some kind as soon as it exists: register each window-scoped bean implementing a
veto interface, attach generated commands to the view they belong to. Asking
every bean to register itself spreads that duty across user code, and scanning
the graph after the fact misses instances created later.

## How it solves it

A `ConstructionListener` contributed with `@IntoSet` is called with every
instance the container builds, and the injector it belongs to:

```java
@Singleton
@IntoSet(ConstructionListener.class)
public final class VetoRegistrar implements ConstructionListener {
    @Inject
    public VetoRegistrar() {}

    @Override
    public void constructed(Object instance, Injector injector) {
        if (instance instanceof ExitVeto veto) {
            injector.getInstance(ExitVetoes.class).add(veto);
        }
    }
}
```

The root injector resolves every listener when it is created. From then on,
the generated factories call each listener synchronously, on the constructing
thread, once an instance is fully built: after its constructor, member
injection and `@PostConstruct`. That covers singletons, unscoped beans,
[child-local](child-injectors.md#child-local-scopes) beans, `@Provides`
results, [interception](interception.md) proxies and
[assisted](assisted-injection.md) products.

The injector passed along is the one the instance belongs to: the root for a
singleton, the child that built an unscoped bean, and for a child-local bean or
a scoped assisted product, the child that hosts it.

## Limitations

- Listeners are root beans: a child injector cannot add its own.
- The listeners themselves, and the instances built while they are being
  resolved (their dependencies), are not reported.
- Instances bound with `bind(...)` on a builder are not reported: the
  container did not build them.
- A listener that throws fails the construction of that instance: it is neither
  cached nor destroyed, and the next resolution builds it again.
- Every listener sees every instance: filtering, by type or otherwise, is the
  listener's job.
