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

### Child-local scopes

A scoping annotation with no `Scope` service is child-local: its beans live in
the children that host it. `scopedChildBuilder(scope)` builds such a child:

```java
@ScopingAnnotation
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface WindowScoped {}

@WindowScoped
public final class Toolbar {
    @Inject
    public Toolbar(Window window) {}
}
```

```java
Injector window = app.scopedChildBuilder(WindowScoped.class)
        .bind(Window.class, frame)
        .build();
Injector view = window.scopedChildBuilder(ViewScoped.class).build();

window.getInstance(Toolbar.class) == view.getInstance(Toolbar.class); // true
```

Each bean of the scope is created once per hosting child, the first time it is
resolved through that child or one of its descendants, and cached there. It is
built through its host, so its dependencies see the host's bindings and those
above it, never those of the descendant that asked for it. Hosts nest: the
nearest one in the chain hosts the bean, so another window opened below a view
gets its own `Toolbar`. A `Provider` injected into a bean resolves through the
injector that built the bean, so a `@ViewScoped` bean's `Provider<Owner>`
returns the `Owner` of its own view, and cycles broken by a `Provider` work as
for singletons.

A child-local bean may depend on keys the root does not bind, such as `Window`
above: its hosts supply them. The root checks them when a host is built
instead: building a child that hosts a scope fails with an
`UnsatisfiedDependencyException` when one of the scope's beans depends on a key
that neither the root, nor that child, nor one of its parents binds. The
message names each bean, key and injection point. A key bound by several hosts
of the chain, such as a `Scope` object each window and each view binds,
resolves to the nearest one.

A bean must not depend directly on one that lives shorter than itself. A
`@Singleton` (or any bean whose scope has a `Scope` service) is built in the
root, where no child-local instance exists, so its direct dependency on a
child-local bean is an error at injector creation. A child-local bean's direct
dependency on a bean of another child-local scope needs a host of that scope
above its own host: building a host without one fails with a
`ScopeResolutionException` naming the bean and the injection point. A
`@WindowScoped` bean may thus depend on a `@ViewScoped` one only in windows
opened below a view, like dialogs. Dependencies through a `Provider` are never
checked, and unscoped beans are rebuilt where they are resolved, so they may
depend on any scope.

Closing a child closes its live children first, then runs `@PreDestroy` on the
instances it hosts, in reverse creation order (see
[lifecycle callbacks](lifecycle-callbacks.md)). Closing the root closes its live
children the same way.

Resolving a child-local bean through an injector with no host of its scope in
its chain fails with a `ScopeResolutionException` naming the bean and the
scope. When no child ever hosted the scope, the message also points out that a
`Scope` service may be missing.

## Limitations

- Child bindings are explicit instances and providers only. A child does not
  discover annotated beans, load factories, or accept `@Provides` classes of
  its own.
- A child hosts at most one scope, and only a child-local one: hosting a
  scope that a `Scope` service handles, such as `@Singleton`, is an error.
- A child-local bean cannot contribute to a [collection](multibindings.md):
  collections are resolved in the root injector, where it has no instance.
  The root injector reports it at creation.
- Other scoped beans belong to the injector whose registry declared them.
- Child bindings cannot contribute to the parent's
  [collections](multibindings.md); a `Set` or `Map` binding resolved through a
  child has the parent's contents.
- Binding the same key twice in one builder is an error, and framework keys
  (`Injector`, `Context`, `Factory`) are not bindable — each injector always
  resolves `Injector` to itself.
- A child holds a reference to its parent; it is a view over the parent's
  graph, not a detachable copy.
- Outside child-local scopes, a child shadows bindings; it cannot supply one
  the root lacks. The root injector checks at creation that every bean's
  dependencies are bound, so a dependency meant to vary per child needs a
  root-level binding too — an app-level default, as `RequestContext` has
  above.
- A child binding for an [interceptor](interception.md) bean's own key shadows
  direct resolution through the child, but not the interceptor chain:
  `ChildContext.interceptor` always delegates to the parent, so intercepted
  methods keep running the parent's interceptor instance regardless of a
  child's override.
