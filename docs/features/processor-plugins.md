# Processor Plugins

## The problem

Some injected values are derived from the injection site itself rather than
resolved from the graph — the canonical case being a logger named after the
class that holds it. A graph binding cannot express "a different value per
declaring class", and writing `Logger.getLogger(...)` in every class is the
boilerplate injection exists to remove.

## How it solves it

The annotation processor is extensible. A plugin pairs a custom annotation,
meta-annotated with `@InjectingAnnotation`, with an `InjectPlugin`
implementation discovered through `ServiceLoader` **on the processor path**,
or on the processor module path (see [module path](module-path.md)).
For each injection site carrying the annotation, the plugin emits the
initializer code that the generated factory will contain — the value is
computed by generated code at build time, not looked up in the registry.

The bundled `@Log` plugin:

```java
@InjectingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Log {}

public final class LogPlugin implements InjectPlugin {
    @Override
    public Optional<CodeBlock> initializer(InjectionSite site) {
        if (site.element().getAnnotation(Log.class) == null) {
            return Optional.empty();
        }
        // emits: Logger.getLogger(<declaring class>.class.getName())
        return Optional.of(CodeBlock.of("$T.getLogger($T.class.getName())",
                LOGGER, ClassName.of(site.beanType())));
    }
}
```

Usage — note the field needs only the plugin's annotation, and the site's
declaring class drives the value:

```java
public final class GreetingService {
    @Log
    Logger logger; // named "…GreetingService"
}

@Singleton
public final class Registry {
    @Inject
    public Registry(@Log Logger logger) { // named "…Registry"
        ...
    }
}
```

Because the plugin runs inside the processor, its output is ordinary generated
source: visible in the build output, debuggable, and free at runtime.

## Packaging

A plugin ships as two artifacts, mirroring `inject` / `inject-processor`:

- `inject-log` holds the `@Log` annotation and depends only on `inject`. It is
  a regular dependency of the code that writes `@Log`.
- `inject-log-processor` holds `LogPlugin` and its `ServiceLoader`
  registration, and depends on `inject-processor` and gaia-processing, whose
  `CodeBlock` the plugin returns. It belongs on the annotation processor path
  only, so neither it nor its code-generation dependencies reach the
  application classpath.

```xml
<dependency>
    <groupId>be.imgn.inject</groupId>
    <artifactId>inject-log</artifactId>
    <version>${inject.version}</version>
</dependency>

<!-- maven-compiler-plugin -->
<annotationProcessorPaths>
    <path>
        <groupId>be.imgn.inject</groupId>
        <artifactId>inject-processor</artifactId>
        <version>${inject.version}</version>
    </path>
    <path>
        <groupId>be.imgn.inject</groupId>
        <artifactId>inject-log-processor</artifactId>
        <version>${inject.version}</version>
    </path>
</annotationProcessorPaths>
```

## Limitations

- A plugin emits an initializer expression for a site; it cannot register
  graph bindings, alter resolution, or observe runtime state. Values needing
  the graph belong in [`@Provides` methods](provides-methods.md).
- Plugin-injected values bypass the registry: they have no key, no scope, and
  cannot be overridden or shadowed at runtime.
- Plugins must be on the compiler's processor path or processor module path
  in every module that uses their annotation; a site whose plugin is absent at
  compile time is not handled.
- Writing a plugin means writing code generation (emitting Java expressions),
  a heavier tool than declaring a binding.
