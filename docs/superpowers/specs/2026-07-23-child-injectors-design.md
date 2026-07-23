# Child injectors — Design

Layer request-, session-, or test-local bindings over an existing injector: a child sees its own instance and
provider bindings first and falls back to its parent, while the parent stays untouched.

## Goals

- `parent.childBuilder().bind(...).bindProvider(...).build()` creates a child injector layering explicit bindings
  over the parent's graph.
- Resolutions through the child see child bindings first — including the dependencies of unscoped parent beans
  built through the child.
- Scoped beans stay coherent: a parent `@Singleton` remains one application-wide instance built from parent
  bindings, shared with every child.

Out of scope (v1): child-registered factories or ServiceLoader layers, child-local scopes, child contributions to
collections, per-child condition re-evaluation.

## User-facing API

```java
public interface Injector {
    /** {@return a builder for a child of this injector} */
    ChildBuilder childBuilder();
}
```

```java
Injector parent = Injector.create();
Injector child = parent.childBuilder()
        .bind(RequestId.class, id)                        // fixed instance
        .bind(Key.of(Conn.class, Names.named("tx")), tx)  // qualified keys work
        .bindProvider(Span.class, () -> tracer.open())    // evaluated per resolution through the child
        .build();
```

`ChildBuilder` carries `bind(Class<T>, T)`, `bind(Key<T>, T)`, `bindProvider(Class<T>, Provider<T>)`,
`bindProvider(Key<T>, Provider<T>)`, and `build()`. For symmetry the root `Injector.Builder` gains the two
`bindProvider` overloads with identical semantics (a distinct name: `bind(key, instance)` cannot overload cleanly
against provider instances).

Children nest: `child.childBuilder()` layers again; the nearest binding wins on the way up the chain.

## Semantics

- **Resolution order**: the child's own bindings first; on a miss, the parent (transitively up the chain).
- **Shadowing**: a child may bind a key its parent binds; the child's binding wins for resolutions through the
  child. The parent's graph and caches are never affected.
- **Scope anchoring**: a scoped binding resolves itself and its dependencies in the injector that owns it. A parent
  `@Singleton` resolved through a child returns the parent's cached instance, built from parent bindings. An
  unscoped (prototype) parent factory resolved through a child is rebuilt with the child as resolution context, so
  its dependencies see child bindings. Shadowing therefore affects direct lookups and prototypes; scoped beans are
  immune by design — this is what keeps a request-scoped override from poisoning an application-wide cache.
- **Provider bindings**: `bindProvider` values are evaluated on every resolution through the child; instance
  bindings always return the same object.
- **Injector self-binding**: `Injector` resolved through the child is the child. By the anchoring rule, a parent
  singleton built in the parent keeps the parent injector; a prototype built through the child gets the child.
- **Framework types**: `ChildBuilder.bind`/`bindProvider` reject `Injector`, `Context`, and `Factory` keys exactly
  like the root builder (`IllegalArgumentException`).
- **Introspection**: child instance/provider bindings carry no `BeanDefinition` (the same rule as root builder
  overrides today); `getBeanDefinition` and `getBeanDefinitionsByStereotype` delegate up the chain unchanged.
- **Multibindings and conditions**: unchanged by children. A child cannot contribute to a collection; conditions
  evaluated at the root stay decided.

## Errors

| Case | Behavior |
|---|---|
| Same key bound twice in one `ChildBuilder` | `IllegalArgumentException` at the second `bind` — layering over the parent is intentional, double-binding within one builder is a typo. (The root builder's documented last-wins behavior is unchanged.) |
| Binding `Injector`/`Context`/`Factory` | `IllegalArgumentException` (existing `requireBindable` rule) |
| Null key/instance/provider | `NullPointerException` |
| Unsatisfied key | existing `UnsatisfiedDependencyException`, after the full parent chain misses — the requester-chain suffix may omit child rebuild frames when the miss surfaces in the parent (each context keeps its own resolution stack) |
| Dependency cycle through a child rebuild | existing `CircularDependencyException`, guarded on the child's resolution path |

## Runtime model

A `ChildContext implements Context` holds the parent `Context`, the local `Map<Key<?>, Provider<?>>`, and the
child's injector reference:

1. `getProvider(key)`: local map hit → that provider. Miss → consult the parent's `BeanDefinition` for the key:
   - definition present and **unscoped** (`!definition.annotationMetadata().hasStereotype(ScopingAnnotation.class)`
     — the metadata already carries the bean's scoping annotations) → return a provider running
     `factory.build(childContext)` under the child's cycle guard (the rebuild path that lets prototypes see child
     bindings);
   - definition present with a scoping stereotype, or no definition (instance/override/built-in/collection
     binding) → delegate to `parent.getProvider(key)` unchanged (anchoring). Collection (`Set`/`Map`) bindings
     have no definition and therefore always resolve in the parent: contributors never see child bindings, matching
     the rule that children cannot affect collections.
2. `Key.of(Injector.class)` is pre-bound in the local map to the child injector (set by the same
   `setInjector`-style attachment used by `DefaultContext`).
3. The processor is untouched: children are a pure runtime feature.

`BeanDefinition` exposure needed for step 1 already exists (`Context.getBeanDefinition`); the factory reference is
the definition itself (`Factory` implements `BeanDefinition`), so no new runtime surface is required beyond
`ChildBuilder`, `ChildContext`, and the `Injector.childBuilder()` method. `ChildContext` decides rebuild-vs-anchor
from the definition's `ScopingAnnotation` stereotype while `DefaultContext` wraps scopes from `Factory.scope()`;
generated factories derive both from the same annotation, which keeps the two views consistent.

## Testing

- **Runtime (`inject`)**: child sees its own instance and provider bindings (provider fresh per resolution);
  shadowing wins through the child and leaves the parent unchanged; parent `@Singleton` resolved through the child
  is the parent's cached instance with parent dependencies; unscoped parent bean resolved through the child gets
  child dependencies; nested children resolve nearest-wins; `Injector` through the child is the child while a
  parent singleton keeps the parent injector; framework-type rejection; double-bind in one builder throws;
  unsatisfied key reports after chain miss; cycle through a child rebuild reports a cycle.
- **End-to-end example**: a classpath example with a request-style child (`RequestId` bound per child; an unscoped
  handler bean sees it; the singleton registry stays shared).

## Decisions log

- Instances + providers only (no child factories/scopes/conditions): covers request/session/test layering with a
  small, runtime-only surface.
- Shadowing allowed — consistent with the root builder's documented override semantics and required by the
  override use cases.
- Scope anchoring over child-local singleton duplicates: "singleton" keeps meaning one per application; shadowing
  deliberately does not reach scoped beans.
- Double-binding within one child builder errors (typo protection); root builder's last-wins stays as documented
  behavior.
