# `inject-maven-plugin`

Registers the framework's `ServiceLoader` providers as `module-info` services, so a modular consumer does not
hand-maintain `provides` clauses. It covers the `be.imgn.inject.context.Factory` providers the annotation processor
generates and the `be.imgn.inject.context.Scope` providers a project registers for any custom scope.

An annotation processor cannot write the consumer's module descriptor, so without this plugin the processor can only
print the factory names in a compile-time note and leave you to copy them into `module-info.java` — and to keep that
list in sync as beans are added or removed.

## Usage

```xml
<plugin>
    <groupId>be.imgn.inject</groupId>
    <artifactId>inject-maven-plugin</artifactId>
    <version>${inject.version}</version>
    <executions>
        <execution>
            <goals><goal>register-factories</goal></goals>
        </execution>
    </executions>
</plugin>
```

The `register-factories` goal binds to `process-classes` by default.

## What it does

- **The project compiles a `module-info.class`** — the descriptor is patched to add a `provides` clause for each
  framework service (`Factory`, and `Scope` when a custom scope is registered), taken from the `META-INF/services`
  files. Any existing clause for those services is replaced, so rebuilds do not accumulate duplicates; clauses for
  other services are left untouched.
- **The project has no descriptor** — one is generated, but only when `generateModuleInfo` is enabled. It requires
  `be.imgn.inject`, exports every package holding compiled classes, and provides the framework services.

Only compiled output is written; the project's sources are never modified.

## Parameters

| Parameter             | Property                   | Default                                 | Meaning                                              |
|-----------------------|----------------------------|-----------------------------------------|------------------------------------------------------|
| `classesDirectory`    |                            | `${project.build.outputDirectory}`      | Compiled output to scan and patch.                   |
| `moduleName`          |                            | `${project.groupId}.${project.artifactId}` | Module name used when generating a descriptor.    |
| `generateModuleInfo`  | `inject.generateModuleInfo`| `false`                                 | Generate a descriptor when the project has none.     |
| `skip`                | `inject.skip`              | `false`                                 | Skip the goal.                                       |

## Notes and limitations

- **Generation is opt-in.** Turning a classpath project into a modular one changes resolution for every downstream
  consumer, and on the classpath the factories already resolve through `META-INF/services`. Enable
  `generateModuleInfo` deliberately.
- **The patched clause exists only in the compiled descriptor.** A `module-info.java` in the source tree will not show
  the `provides` line the artifact carries. This is inherent to patching bytecode rather than sources.
- **Not used by this repository's own example modules.** Maven resolves plugins from the repository rather than the
  reactor, so a module in this build cannot consume a plugin built by the same build without installing it first.
  `inject-examples-module` therefore keeps a hand-written `provides` clause.
