# Child Injectors

## The problem

An application graph is mostly stable, but some bindings are contextual: the
current request's data, a tenant's configuration. Rebuilding a whole injector
per request is wasteful; mutating the shared one is unsafe and wrong for
concurrent contexts.

## How it solves it

`childBuilder()` derives a lightweight child injector: a small layer of
explicit bindings over the parent's full graph. Resolution checks the nearest
child layer first, walking up through intermediate children, and falls back to
the parent. The parent is never modified and keeps serving its own resolutions
unchanged.

```java
Injector app = Injector.create();

Injector requestA = app.childBuilder()
        .bind(RequestContext.class, new RequestContext("ada"))
        .build();
Injector requestB = app.childBuilder()
        .bind(RequestContext.class, new RequestContext("bob"))
        .build();

requestA.getInstance(RequestGreeter.class).request().user(); // "ada"
requestB.getInstance(RequestGreeter.class).request().user(); // "bob"
app.getInstance(RequestGreeter.class).request().user();      // the app-level default
```

Shadowing works through dependencies, not just direct lookups: an unscoped
parent bean resolved through a child is rebuilt at the child, so its own
dependencies see the child's bindings (that is why `RequestGreeter` above
picks up each request's `RequestContext`). Scoped beans anchor to the
injector that declared them — the parent's `@Singleton` stays one instance,
shared by parent and all children.

Besides instances, a child can bind a provider, evaluated on every resolution
through the child:

```java
Injector session = app.childBuilder()
        .bindProvider(Token.class, tokenSource::current)
        .build();
```

Children nest: a child's `childBuilder()` layers again, and lookups honor the
nearest binding.

## Limitations

- Child bindings are explicit instances and providers only. A child does not
  discover annotated beans, load factories, or accept `@Provides` classes of
  its own.
- No child-local scopes: a child cannot declare "singleton per child". A
  scoped bean belongs to the injector whose registry declared it.
- Child bindings cannot contribute to the parent's
  [collections](multibindings.md); a `Set` or `Map` binding resolved through a
  child has the parent's contents.
- Binding the same key twice in one builder is an error, and framework keys
  (`Injector`, `Context`, `Factory`) are not bindable — each injector always
  resolves `Injector` to itself.
- A child holds a reference to its parent; it is a view over the parent's
  graph, not a detachable copy.
- A child shadows bindings; it cannot supply one the root lacks. The root
  injector checks at creation that every bean's dependencies are bound, so a
  dependency meant to vary per child needs a root-level binding too — an
  app-level default, as `RequestContext` has above.
- A child binding for an [interceptor](interception.md) bean's own key shadows
  direct resolution through the child, but not the interceptor chain:
  `ChildContext.interceptor` always delegates to the parent, so intercepted
  methods keep running the parent's interceptor instance regardless of a
  child's override.
