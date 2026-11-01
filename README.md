# imgn inject

A dependency injection framework for Java 21 or later that uses no reflection: an annotation processor generates a
factory for each bean at compile time, and the injector finds those factories through `ServiceLoader`, on the class
path and on the module path.

Guide: [imgn-dev.github.io/inject](https://imgn-dev.github.io/inject/). API documentation:
[latest](https://imgn-dev.github.io/inject/api/latest/), or
[every released version](https://imgn-dev.github.io/inject/api/).

## Artifacts

| Artifact              | Module                     | What it does                                                    |
|-----------------------|----------------------------|-----------------------------------------------------------------|
| `inject`              | `be.imgn.inject`           | The annotations and the injector                                |
| `inject-processor`    | `be.imgn.inject.processor` | The annotation processor, and the SPI of its plugins            |
| `inject-maven-plugin` |                            | Registers the generated factories in the compiled `module-info` |

## Using inject

Depend on `inject`, and run `inject-processor` as an annotation processor:

```xml
<dependencies>
  <dependency>
    <groupId>be.imgn.inject</groupId>
    <artifactId>inject</artifactId>
    <version>${inject.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>be.imgn.inject</groupId>
            <artifactId>inject-processor</artifactId>
            <version>${inject.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Annotate the beans, then create the injector:

```java
public class Messages { ... }

@Singleton
public class Greeter {
    @Inject
    public Greeter(Messages messages) { ... }
}

Injector injector = Injector.create(); // finds every generated factory
Greeter greeter = injector.getInstance(Greeter.class);
```

The processor claims no annotation, so a build compiling with `-Xlint:all -Werror` needs `-Xlint:all,-processing`.
On the module path, require `be.imgn.inject` and declare the generated factories in `module-info.java`, by hand or
with `inject-maven-plugin`. The [guide](https://imgn-dev.github.io/inject/) has one page per feature.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
