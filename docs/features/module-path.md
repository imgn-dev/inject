# Classpath and Module Path Support

## The problem

Factory discovery has to work in both worlds Java applications actually run
in: the flat classpath and the module path with its explicit `provides`
directives and encapsulation rules. A mechanism that relies on runtime
classpath scanning or on breaking module encapsulation fails exactly where
the module system is strictest.

## How it solves it

Discovery is plain `ServiceLoader` over generated `Factory` implementations —
the one mechanism both worlds support natively, with no scanning and no
reflective access into foreign packages.

**Classpath** — fully automatic. The processor writes
`META-INF/services/be.imgn.inject.context.Factory` entries for every factory
it generates, merging with existing entries. Compile and run:

```java
Injector injector = Injector.create(); // finds every generated factory
```

**Module path** — the module's descriptor must declare what it provides. The
processor cannot edit a hand-written `module-info.java`, so there are two
supported routes:

1. **Manual.** List the generated factories (the processor prints their names
   in a compile-time note):

   ```java
   module my.app {
       requires be.imgn.inject;

       provides be.imgn.inject.context.Factory with
               my.app.$Widget$$Factory,
               my.app.$Service$$Factory;
   }
   ```

2. **Build plugin.** The `inject-maven-plugin` goal `register-factories`
   (bound to `process-classes`) reads the generated service files and patches
   the compiled `module-info.class` with the matching `provides` directives —
   no factory names in source. With `generateModuleInfo=true` it can even
   synthesize a descriptor for a project that has none; `inject.skip` turns
   the goal off.

Because factories are ordinary compiled classes emitted into each bean's own
package, module encapsulation is never bypassed: a module injects its own
beans with its own generated code, and exports nothing extra to make that
work.

**The processor** runs from either path. On the processor path, javac finds it
through its service file. It is also the module `be.imgn.inject.processor`,
which provides `javax.annotation.processing.Processor`, so javac finds it on
the processor module path (`--processor-module-path`) too. That path then also
holds the modules it requires: `inject`, `gaia-processing`, `gaia-core`,
`jspecify` and `error_prone_annotations`.

A [processor plugin](processor-plugins.md) on the processor module path must
provide `be.imgn.inject.processor.spi.InjectPlugin` in its descriptor; a
plugin jar without a descriptor is an automatic module, whose service file
counts as that declaration.

## Limitations

- On the module path, factory registration is a consumer-side obligation:
  keep the manual `provides` list in sync with the processor's note, or adopt
  the build plugin. A factory missing from the descriptor is simply not
  discovered, surfacing as `UnsatisfiedDependencyException`: at injector
  creation when a bean depends on it, otherwise when it is requested.
- The build plugin is Maven-only; other build tools take the manual route.
- All `ServiceLoader`-discovered extension points — factories,
  [scopes](scopes.md), [conditions](conditional-beans.md) — share this
  registration story; on the module path each needs its `provides` directive.
