# Cycle Detection

## The problem

If `A` needs `B` and `B` needs `A`, neither can be constructed first.
Undetected, a dependency cycle becomes infinite recursion and a stack
overflow, far from the code that caused it and with no hint of the loop's
shape.

## How it solves it

Two layers, both reporting the cycle as a readable path.

**Compile time.** The processor builds the dependency graph of the classes it
compiles and rejects constructor cycles it can see as compile errors — the
loop is reported before anything runs.

**Runtime.** Because the graph is assembled from factories loaded at runtime,
compilation cannot see across separately built artifacts. Every resolution
therefore runs through a per-thread resolution stack: entering a key that is
already being resolved on that thread fails immediately with
`CircularDependencyException` and the full chain:

```
CircularDependencyException: Dependency cycle: Key[A] -> Key[B] -> Key[A]
```

The guard is per-thread, adds a stack push/pop per resolution, and never
produces a stack overflow or a deadlock — detection is immediate on the first
repeated key.

### Breaking a legitimate cycle

A cycle where one participant only needs the other *later* is expressed with
[`Provider<T>`](provider-injection.md): injecting the provider defers that
edge past construction, so resolution completes.

```java
public final class A {
    @Inject
    public A(Provider<B> b) { // no B is built while A is being built
        this.b = b;
    }
}
```

The deferral must be real: calling `provider.get()` while construction is
still on the stack re-enters the cycle and fails the same way.

## Limitations

- Compile-time detection covers constructor cycles among classes visible to
  one compilation. Cycles that cross separately compiled artifacts surface at
  runtime, on the first resolution that closes the loop — not at injector
  creation.
- There is no automatic cycle breaking: no proxies, no partially-constructed
  instances. Every cycle is an error until a `Provider` edge makes the
  deferral explicit.
