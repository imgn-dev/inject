# Runtime Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the runtime engine of the `be.imgn.inject` DI framework — the public API (`Key`, `Provider`, `Injector`, annotations) and the hidden engine (`Context`, `Factory`, `Scope`, injection points, exceptions) — buildable and fully unit-tested with hand-written factories, before any annotation processor exists.

**Architecture:** Everything is a `Provider<T>`. `Injector` delegates to a `Context` that `ServiceLoader`s all `Factory` and `Scope` providers at construction, builds an immutable `Key → Provider` registry, wraps each `FactoryProvider` with its declared scope, and resolves dependencies lazily with a thread-local cycle guard. Generated factory subclasses (later plans) live in the bean's package and extend the `public` `Factory` base; this plan supplies that base and proves it works with hand-written factories.

**Tech Stack:** Java 21, Maven (`./mvnw`), JUnit 6, AssertJ 3.27.7, Error Prone + NullAway, Checkstyle, Spotless (Palantir), JaCoCo, JSpecify `@Nullable`.

## Global Constraints

- Java 21. Build command is **`./mvnw clean verify`** — never `install`. Always use the wrapper `./mvnw`.
- Packages: **`be.imgn.inject`** = `Key`, `Provider`, `Injector`, all annotations, exceptions (public API). **`be.imgn.inject.context`** = `Context`, `Factory`, `FactoryProvider`, `*InjectionPoint`, `Scope`, `SingletonProvider`, `SingletonScope` (public types, conceptually internal).
- Zero reflection in the resolution path (runtime resolves through generated/hand-written `Factory` subclasses only).
- Default scope is **prototype** (new instance per `get()`); scopes are opt-in wrappers.
- Thread-safe: `Context` registry immutable after construction; `SingletonProvider` instantiates exactly once under contention.
- Every public/protected method validates inputs: null → `NullPointerException` (`Objects.requireNonNull(arg, "name")`); bad range → `IllegalArgumentException`; bad state → `IllegalStateException`. Validate at the boundary before any work.
- Qualifier identity = annotation type + attribute values. Marker qualifiers match by type alone.
- Tests: JUnit 6 + AssertJ, **fluent chaining** (one `assertThat(x)` with chained assertions, never repeated `assertThat(x)` on the same subject).
- NullAway `AnnotatedPackages=be.imgn.inject`.
- Docs (incl. Javadoc on public API) written and kept current; describe current state only, no historical asides.

---

## File Structure

**Build / tooling (repo root + `inject/`):**
- `pom.xml` — parent (`be.imgn.inject:inject-parent`, packaging `pom`), all `pluginManagement`, `dependencyManagement`, version properties.
- `inject/pom.xml` — runtime module.
- `checkstyle.xml` — no-FQN-in-code rule.
- `.mvn/jvm.config` — javac-internals `--add-exports/--add-opens`.
- `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd` — wrapper.
- `inject/src/main/java/module-info.java` — `module be.imgn.inject`.

**Public API — `inject/src/main/java/be/imgn/inject/`:**
- `Provider.java`, `Qualifier.java`, `Key.java`, `SimpleKey.java`, `QualifiedKey.java`, `Injector.java`, `DefaultInjector.java` (package-private).
- Annotations: `Inject.java`, `Provides.java`, `InjectingAnnotation.java`, `QualifyingAnnotation.java`, `ScopingAnnotation.java`, `Named.java`, `Singleton.java`.
- Exceptions: `InjectionException.java`, `UnsatisfiedDependencyException.java`, `CircularDependencyException.java`, `DuplicateBindingException.java`, `ScopeResolutionException.java`.

**Engine — `inject/src/main/java/be/imgn/inject/context/`:**
- `InjectionPoint.java`, `ConstructorInjectionPoint.java`, `MethodInjectionPoint.java`, `FieldInjectionPoint.java`.
- `Factory.java`, `FactoryProvider.java`, `Context.java`, `DefaultContext.java` (package-private), `CycleGuardProvider.java` (package-private).
- `Scope.java`, `SingletonProvider.java`, `SingletonScope.java`.
- `inject/src/main/resources/META-INF/services/be.imgn.inject.context.Scope` — registers `SingletonScope`.

**Tests — `inject/src/test/java/...`** mirror the above.

---

## Task 1: Buildable project skeleton

**Files:**
- Create: `pom.xml`, `inject/pom.xml`, `checkstyle.xml`, `.mvn/jvm.config`, `.mvn/wrapper/maven-wrapper.properties`, `inject/src/main/java/module-info.java`, `inject/src/main/java/be/imgn/inject/package-info.java`
- Test: `inject/src/test/java/be/imgn/inject/BuildSmokeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a runnable `./mvnw clean verify` and the `be.imgn.inject` module.

- [ ] **Step 1: Write the parent `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>be.imgn.inject</groupId>
    <artifactId>inject-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>inject</module>
    </modules>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.release>21</maven.compiler.release>

        <maven-compiler-plugin.version>3.15.0</maven-compiler-plugin.version>
        <errorprone.version>2.48.0</errorprone.version>
        <nullaway.version>0.13.1</nullaway.version>
        <jspecify.version>1.0.0</jspecify.version>
        <maven-checkstyle-plugin.version>3.6.0</maven-checkstyle-plugin.version>
        <checkstyle.version>13.4.2</checkstyle.version>
        <spotless-plugin.version>3.5.1</spotless-plugin.version>
        <versions-plugin.version>2.21.0</versions-plugin.version>
        <maven-surefire-plugin.version>3.5.5</maven-surefire-plugin.version>
        <mockito.version>5.23.0</mockito.version>
        <jacoco-plugin.version>0.8.14</jacoco-plugin.version>
        <maven-javadoc-plugin.version>3.12.0</maven-javadoc-plugin.version>
        <junit.version>6.1.0</junit.version>
        <assertj.version>3.27.7</assertj.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>${junit.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.assertj</groupId>
                <artifactId>assertj-core</artifactId>
                <version>${assertj.version}</version>
            </dependency>
            <dependency>
                <groupId>org.jspecify</groupId>
                <artifactId>jspecify</artifactId>
                <version>${jspecify.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>${maven-compiler-plugin.version}</version>
                    <configuration>
                        <fork>true</fork>
                        <compilerArgs>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
                            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
                            <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                            <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED</arg>
                            <arg>-XDcompilePolicy=simple</arg>
                            <arg>--should-stop=ifError=FLOW</arg>
                            <arg>-Xplugin:ErrorProne
                                -XepDisableWarningsInGeneratedCode
                                -XepExcludedPaths:.*/target/generated-sources/.*
                                -Xep:NullAway:ERROR
                                -XepOpt:NullAway:AnnotatedPackages=be.imgn.inject
                                -Xep:InvalidInlineTag:OFF
                                -Xep:InvalidLink:OFF
                                -Xep:AnnotateFormatMethod:OFF
                                -Xep:ImmutableEnumChecker:OFF
                            </arg>
                        </compilerArgs>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>com.google.errorprone</groupId>
                                <artifactId>error_prone_core</artifactId>
                                <version>${errorprone.version}</version>
                            </path>
                            <path>
                                <groupId>com.uber.nullaway</groupId>
                                <artifactId>nullaway</artifactId>
                                <version>${nullaway.version}</version>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>com.diffplug.spotless</groupId>
                    <artifactId>spotless-maven-plugin</artifactId>
                    <version>${spotless-plugin.version}</version>
                    <configuration>
                        <java>
                            <excludes>
                                <exclude>**/module-info.java</exclude>
                                <exclude>**/package-info.java</exclude>
                            </excludes>
                            <palantirJavaFormat>
                                <version>2.83.0</version>
                                <style>PALANTIR</style>
                                <formatJavadoc>true</formatJavadoc>
                            </palantirJavaFormat>
                            <removeUnusedImports/>
                            <importOrder>
                                <order>java|javax,com,org,</order>
                            </importOrder>
                            <trimTrailingWhitespace/>
                            <endWithNewline/>
                        </java>
                    </configuration>
                    <executions>
                        <execution>
                            <id>spotless-apply</id>
                            <phase>validate</phase>
                            <goals><goal>apply</goal></goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-checkstyle-plugin</artifactId>
                    <version>${maven-checkstyle-plugin.version}</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.puppycrawl.tools</groupId>
                            <artifactId>checkstyle</artifactId>
                            <version>${checkstyle.version}</version>
                        </dependency>
                    </dependencies>
                    <configuration>
                        <configLocation>${maven.multiModuleProjectDirectory}/checkstyle.xml</configLocation>
                        <consoleOutput>true</consoleOutput>
                        <failsOnError>true</failsOnError>
                        <includeTestSourceDirectory>true</includeTestSourceDirectory>
                    </configuration>
                    <executions>
                        <execution>
                            <id>validate</id>
                            <phase>validate</phase>
                            <goals><goal>check</goal></goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>${maven-surefire-plugin.version}</version>
                    <configuration>
                        <argLine>@{argLine} --sun-misc-unsafe-memory-access=allow</argLine>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.jacoco</groupId>
                    <artifactId>jacoco-maven-plugin</artifactId>
                    <version>${jacoco-plugin.version}</version>
                    <executions>
                        <execution>
                            <id>prepare-agent</id>
                            <goals><goal>prepare-agent</goal></goals>
                        </execution>
                        <execution>
                            <id>report</id>
                            <phase>test</phase>
                            <goals><goal>report</goal></goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>versions-maven-plugin</artifactId>
                    <version>${versions-plugin.version}</version>
                    <configuration>
                        <ruleSet>
                            <ignoreVersions>
                                <ignoreVersion>
                                    <type>regex</type>
                                    <version>(.+-SNAPSHOT|.+-M\d+|.+-RC\d+)</version>
                                </ignoreVersion>
                                <ignoreVersion>
                                    <type>regex</type>
                                    <version>.+-(alpha|beta).+</version>
                                </ignoreVersion>
                            </ignoreVersions>
                        </ruleSet>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>${maven-javadoc-plugin.version}</version>
                    <configuration>
                        <links>
                            <link>https://docs.oracle.com/en/java/javase/21/docs/api/</link>
                        </links>
                        <doclint>all,-missing</doclint>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>

        <plugins>
            <plugin>
                <groupId>com.diffplug.spotless</groupId>
                <artifactId>spotless-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write `inject/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>be.imgn.inject</groupId>
        <artifactId>inject-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>inject</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.jspecify</groupId>
            <artifactId>jspecify</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Write `checkstyle.xml`** (no-FQN-in-code rule)

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <property name="severity" value="error"/>
    <property name="fileExtensions" value="java"/>

    <module name="TreeWalker">
        <module name="AvoidStarImport">
            <property name="severity" value="warning"/>
        </module>
        <module name="IllegalImport">
            <property name="illegalPkgs" value="sun"/>
        </module>
        <module name="RedundantImport"/>
        <module name="UnusedImports"/>
        <module name="RegexpSinglelineJava">
            <property name="id" value="NoFQNInCode"/>
            <property name="format"
                      value="^\s*(?!import\s|package\s|module\s|\*|//|/\*|\s*\*).*\b([a-z][a-z0-9]*\.){2,}[A-Z][a-zA-Z0-9]*\b"/>
            <property name="ignoreComments" value="true"/>
            <property name="message" value="Avoid fully qualified names in code. Use imports instead."/>
        </module>
    </module>

    <module name="BeforeExecutionExclusionFileFilter">
        <property name="fileNamePattern" value="module-info\.java$"/>
    </module>
    <module name="SuppressionSingleFilter">
        <property name="files" value="module-info\.java"/>
        <property name="checks" value="RegexpSinglelineJava"/>
        <property name="id" value="NoFQNInCode"/>
    </module>
    <module name="SuppressionSingleFilter">
        <property name="files" value="package-info\.java"/>
        <property name="checks" value="RegexpSinglelineJava"/>
        <property name="id" value="NoFQNInCode"/>
    </module>
</module>
```

- [ ] **Step 4: Write `.mvn/jvm.config`** (one flag per line, no blank lines)

```
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

- [ ] **Step 5: Generate the Maven wrapper**

Run: `mvn -N wrapper:wrapper -Dmaven=3.9.14`
Then overwrite `.mvn/wrapper/maven-wrapper.properties` with:

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.14/apache-maven-3.9.14-bin.zip
```

Expected: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` exist.

- [ ] **Step 6: Write `inject/src/main/java/module-info.java`**

```java
/** The zero-reflection dependency injection runtime. */
module be.imgn.inject {
    requires static org.jspecify;

    exports be.imgn.inject;
    exports be.imgn.inject.context;

    uses be.imgn.inject.context.Factory;
    uses be.imgn.inject.context.Scope;

    provides be.imgn.inject.context.Scope with be.imgn.inject.context.SingletonScope;
}
```

- [ ] **Step 7: Write `inject/src/main/java/be/imgn/inject/package-info.java`**

```java
/** Public API of the {@code be.imgn.inject} dependency injection framework. */
@NullMarked
package be.imgn.inject;

import org.jspecify.annotations.NullMarked;
```

- [ ] **Step 8: Write the smoke test**

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {
    @Test
    void moduleBuilds() {
        assertThat(getClass().getModule().getName()).isEqualTo("be.imgn.inject");
    }
}
```

Note: Step 6 references `SingletonScope`, which is created in Task 7. Until then, temporarily comment out the two `uses`/`provides` lines and the `provides` line so the module compiles; Task 7 re-enables them. (Alternatively, defer Step 6's service lines — but keep `exports`.)

- [ ] **Step 9: Run the build**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS; `BuildSmokeTest` passes.

- [ ] **Step 10: Commit**

```bash
git add pom.xml inject/pom.xml checkstyle.xml .mvn mvnw mvnw.cmd inject/src
git commit -m "build: scaffold parent POM, tooling, and inject module"
```

---

## Task 2: `Provider`, `Qualifier`, and the sealed `Key`

**Files:**
- Create: `be/imgn/inject/Provider.java`, `Qualifier.java`, `Key.java`, `SimpleKey.java`, `QualifiedKey.java`
- Test: `be/imgn/inject/KeyTest.java`, `QualifierTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Provider<T>.get()`; `Qualifier.of(Class)`, `new Qualifier(Class, Map)`; `Key.of(Class)`, `Key.of(Class, Qualifier)`, `Key.type()`, `Key.qualifier(): Optional<Qualifier>`; record types `SimpleKey<T>(Class<T> type)`, `QualifiedKey<T>(Class<T> type, Qualifier qualifierValue)`.

- [ ] **Step 1: Write failing tests for `Key` and `Qualifier`**

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KeyTest {
    @Test
    void simpleKeyHasTypeAndNoQualifier() {
        Key<String> key = Key.of(String.class);
        assertThat(key)
                .isInstanceOf(SimpleKey.class)
                .returns(String.class, Key::type)
                .returns(Optional.empty(), Key::qualifier);
    }

    @Test
    void qualifiedKeysDifferByAttributeValue() {
        Qualifier db = new Qualifier(Named.class, Map.of("value", "db"));
        Qualifier cache = new Qualifier(Named.class, Map.of("value", "cache"));
        assertThat(Key.of(String.class, db))
                .isInstanceOf(QualifiedKey.class)
                .isEqualTo(Key.of(String.class, db))
                .isNotEqualTo(Key.of(String.class, cache));
    }

    @Test
    void ofRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> Key.of(null));
    }
}
```

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QualifierTest {
    @Test
    void markerQualifiersMatchByTypeAlone() {
        assertThat(Qualifier.of(Named.class))
                .isEqualTo(Qualifier.of(Named.class))
                .isEqualTo(new Qualifier(Named.class, Map.of()));
    }
}
```

Note: `Named` is created in Task 3. To keep Task 2 self-contained, this test uses `Named.class` as a stand-in annotation type; if executing strictly in order, temporarily declare a private `@interface Named {}` in the test file and remove it in Task 3. (Recommended: run Task 3's annotation creation of `Named` first if executing out of order.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl inject test -Dtest=KeyTest,QualifierTest`
Expected: FAIL — `Provider`/`Key`/`Qualifier` do not exist.

- [ ] **Step 3: Write `Provider`**

```java
package be.imgn.inject;

/**
 * The core abstraction: supplies an instance of {@code T} on demand.
 *
 * @param <T> the provided type
 */
@FunctionalInterface
public interface Provider<T> {
    /** {@return a provided instance of {@code T}} */
    T get();
}
```

- [ ] **Step 4: Write `Qualifier`**

```java
package be.imgn.inject;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies a binding beyond its type. Identity is the qualifier annotation type
 * together with its attribute values, so {@code @Named("db")} and
 * {@code @Named("cache")} are distinct. Marker qualifiers carry no attributes and
 * match by type alone.
 *
 * <p>Attribute values must be value-based (primitives, {@code String}, {@code Class},
 * enums, or {@code List}s of these); array-valued attributes must be supplied as
 * {@code List}s so equality is by value.
 */
public final class Qualifier {
    private final Class<? extends Annotation> annotationType;
    private final Map<String, Object> attributes;

    /**
     * @param annotationType the qualifier annotation type
     * @param attributes the qualifier's attribute values, by attribute name
     */
    public Qualifier(Class<? extends Annotation> annotationType, Map<String, Object> attributes) {
        this.annotationType = Objects.requireNonNull(annotationType, "annotationType");
        this.attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * {@return a marker qualifier with no attributes}
     *
     * @param annotationType the qualifier annotation type
     */
    public static Qualifier of(Class<? extends Annotation> annotationType) {
        return new Qualifier(annotationType, Map.of());
    }

    /** {@return the qualifier annotation type} */
    public Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    /** {@return the qualifier's attribute values, by attribute name} */
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Qualifier other
                && annotationType.equals(other.annotationType)
                && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(annotationType, attributes);
    }

    @Override
    public String toString() {
        return "@" + annotationType.getSimpleName() + attributes;
    }
}
```

- [ ] **Step 5: Write `Key`, `SimpleKey`, `QualifiedKey`**

```java
package be.imgn.inject;

import java.util.Objects;
import java.util.Optional;

/**
 * A binding identity: a type, optionally combined with a {@link Qualifier}.
 *
 * @param <T> the bound type
 */
public sealed interface Key<T> permits SimpleKey, QualifiedKey {
    /** {@return the bound type} */
    Class<T> type();

    /** {@return the qualifier, or empty for an unqualified key} */
    Optional<Qualifier> qualifier();

    /**
     * {@return an unqualified key for {@code type}}
     *
     * @param type the bound type
     * @param <T> the bound type
     */
    static <T> Key<T> of(Class<T> type) {
        return new SimpleKey<>(type);
    }

    /**
     * {@return a qualified key}
     *
     * @param type the bound type
     * @param qualifier the qualifier
     * @param <T> the bound type
     */
    static <T> Key<T> of(Class<T> type, Qualifier qualifier) {
        return new QualifiedKey<>(type, qualifier);
    }
}
```

```java
package be.imgn.inject;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Key} with no qualifier.
 *
 * @param type the bound type
 * @param <T> the bound type
 */
public record SimpleKey<T>(Class<T> type) implements Key<T> {
    /** @param type the bound type */
    public SimpleKey {
        Objects.requireNonNull(type, "type");
    }

    @Override
    public Optional<Qualifier> qualifier() {
        return Optional.empty();
    }
}
```

```java
package be.imgn.inject;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Key} carrying a {@link Qualifier}.
 *
 * @param type the bound type
 * @param qualifierValue the qualifier
 * @param <T> the bound type
 */
public record QualifiedKey<T>(Class<T> type, Qualifier qualifierValue) implements Key<T> {
    /**
     * @param type the bound type
     * @param qualifierValue the qualifier
     */
    public QualifiedKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(qualifierValue, "qualifierValue");
    }

    @Override
    public Optional<Qualifier> qualifier() {
        return Optional.of(qualifierValue);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -pl inject test -Dtest=KeyTest,QualifierTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/Provider.java \
        inject/src/main/java/be/imgn/inject/Qualifier.java \
        inject/src/main/java/be/imgn/inject/Key.java \
        inject/src/main/java/be/imgn/inject/SimpleKey.java \
        inject/src/main/java/be/imgn/inject/QualifiedKey.java \
        inject/src/test/java/be/imgn/inject/KeyTest.java \
        inject/src/test/java/be/imgn/inject/QualifierTest.java
git commit -m "feat: add Provider, Qualifier, and sealed Key hierarchy"
```

---

## Task 3: Annotations

**Files:**
- Create: `be/imgn/inject/Inject.java`, `Provides.java`, `InjectingAnnotation.java`, `QualifyingAnnotation.java`, `ScopingAnnotation.java`, `Named.java`, `Singleton.java`
- Test: `be/imgn/inject/AnnotationContractTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: annotation types `@Inject`, `@Provides`, `@InjectingAnnotation`, `@QualifyingAnnotation`, `@ScopingAnnotation`, `@Named` (has `String value()`), `@Singleton`.

- [ ] **Step 1: Write the failing contract test**

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

class AnnotationContractTest {
    @Test
    void metaAnnotationsTargetAnnotationTypeOnly() {
        assertThat(QualifyingAnnotation.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.ANNOTATION_TYPE);
        assertThat(ScopingAnnotation.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.ANNOTATION_TYPE);
        assertThat(InjectingAnnotation.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.ANNOTATION_TYPE);
    }

    @Test
    void namedIsAQualifyingAnnotationWithValue() throws Exception {
        assertThat(Named.class.isAnnotationPresent(QualifyingAnnotation.class)).isTrue();
        assertThat(Named.class.getMethod("value").getReturnType()).isEqualTo(String.class);
    }

    @Test
    void singletonIsAScopingAnnotation() {
        assertThat(Singleton.class.isAnnotationPresent(ScopingAnnotation.class)).isTrue();
    }

    @Test
    void injectTargetsMembersAndRetainsAtRuntimeForTests() {
        assertThat(Inject.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl inject test -Dtest=AnnotationContractTest`
Expected: FAIL — annotation types do not exist.

- [ ] **Step 3: Write the meta-annotations**

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Meta-annotation marking an annotation as a qualifier (contributes to a {@link Key}). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface QualifyingAnnotation {}
```

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Meta-annotation marking an annotation as a scope selector. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ScopingAnnotation {}
```

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Meta-annotation marking an annotation as a plugin-handled injecting annotation. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface InjectingAnnotation {}
```

- [ ] **Step 4: Write `@Inject` and `@Provides`**

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a constructor, method, or field as an injection point. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
public @interface Inject {}
```

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a method that supplies an instance of its return type. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Provides {}
```

- [ ] **Step 5: Write the built-in `@Named` and `@Singleton`**

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Built-in string qualifier. */
@QualifyingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface Named {
    /** {@return the qualifier name} */
    String value();
}
```

```java
package be.imgn.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Built-in scope: exactly one instance per injector. */
@ScopingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Singleton {}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -pl inject test -Dtest=AnnotationContractTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/Inject.java \
        inject/src/main/java/be/imgn/inject/Provides.java \
        inject/src/main/java/be/imgn/inject/InjectingAnnotation.java \
        inject/src/main/java/be/imgn/inject/QualifyingAnnotation.java \
        inject/src/main/java/be/imgn/inject/ScopingAnnotation.java \
        inject/src/main/java/be/imgn/inject/Named.java \
        inject/src/main/java/be/imgn/inject/Singleton.java \
        inject/src/test/java/be/imgn/inject/AnnotationContractTest.java
git commit -m "feat: add core and meta annotations plus @Named and @Singleton"
```

---

## Task 4: Exception hierarchy

**Files:**
- Create: `be/imgn/inject/InjectionException.java`, `UnsatisfiedDependencyException.java`, `CircularDependencyException.java`, `DuplicateBindingException.java`, `ScopeResolutionException.java`
- Test: `be/imgn/inject/ExceptionsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `InjectionException(String)` / `(String, Throwable)`; subtypes `UnsatisfiedDependencyException(String)`, `CircularDependencyException(String)`, `DuplicateBindingException(String)`, `ScopeResolutionException(String)`.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExceptionsTest {
    @Test
    void subtypesAreInjectionExceptions() {
        assertThat(new UnsatisfiedDependencyException("x"))
                .isInstanceOf(InjectionException.class)
                .hasMessage("x");
        assertThat(new CircularDependencyException("a -> b -> a"))
                .isInstanceOf(InjectionException.class)
                .hasMessageContaining("-> a");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl inject test -Dtest=ExceptionsTest`
Expected: FAIL — exception types do not exist.

- [ ] **Step 3: Write the base and subtypes**

```java
package be.imgn.inject;

/** Base type for all runtime injection failures. */
public class InjectionException extends RuntimeException {
    /** @param message the detail message */
    public InjectionException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause the underlying cause
     */
    public InjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package be.imgn.inject;

/** Thrown when no factory can satisfy a requested {@link Key}. */
public class UnsatisfiedDependencyException extends InjectionException {
    /** @param message the detail message */
    public UnsatisfiedDependencyException(String message) {
        super(message);
    }
}
```

```java
package be.imgn.inject;

/** Thrown when a dependency cycle is detected at resolution time. */
public class CircularDependencyException extends InjectionException {
    /** @param message the detail message, including the cycle path */
    public CircularDependencyException(String message) {
        super(message);
    }
}
```

```java
package be.imgn.inject;

/** Thrown when two factories expose the same {@link Key}. */
public class DuplicateBindingException extends InjectionException {
    /** @param message the detail message */
    public DuplicateBindingException(String message) {
        super(message);
    }
}
```

```java
package be.imgn.inject;

/** Thrown when a scoping annotation has no registered {@link be.imgn.inject.context.Scope}. */
public class ScopeResolutionException extends InjectionException {
    /** @param message the detail message */
    public ScopeResolutionException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl inject test -Dtest=ExceptionsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/InjectionException.java \
        inject/src/main/java/be/imgn/inject/UnsatisfiedDependencyException.java \
        inject/src/main/java/be/imgn/inject/CircularDependencyException.java \
        inject/src/main/java/be/imgn/inject/DuplicateBindingException.java \
        inject/src/main/java/be/imgn/inject/ScopeResolutionException.java \
        inject/src/test/java/be/imgn/inject/ExceptionsTest.java
git commit -m "feat: add injection exception hierarchy"
```

---

## Task 5: Injection points

**Files:**
- Create: `be/imgn/inject/context/InjectionPoint.java`, `ConstructorInjectionPoint.java`, `MethodInjectionPoint.java`, `FieldInjectionPoint.java`, `be/imgn/inject/context/package-info.java`
- Test: `be/imgn/inject/context/InjectionPointTest.java`

**Interfaces:**
- Consumes: `Key` (Task 2).
- Produces: `InjectionPoint` (sealed) with `List<Key<?>> keys()`; `ConstructorInjectionPoint(List<Key<?>>)` + static `none()`; `MethodInjectionPoint(String name, List<Key<?>>)`; `FieldInjectionPoint(String name, Key<?> key)`.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.inject.Key;
import java.util.List;
import org.junit.jupiter.api.Test;

class InjectionPointTest {
    @Test
    void constructorPointExposesItsKeys() {
        ConstructorInjectionPoint point =
                new ConstructorInjectionPoint(List.of(Key.of(String.class), Key.of(Integer.class)));
        assertThat(point.keys()).containsExactly(Key.of(String.class), Key.of(Integer.class));
        assertThat(ConstructorInjectionPoint.none().keys()).isEmpty();
    }

    @Test
    void fieldPointExposesSingletonKeyList() {
        FieldInjectionPoint point = new FieldInjectionPoint("logger", Key.of(String.class));
        assertThat(point)
                .returns("logger", FieldInjectionPoint::name)
                .returns(List.of(Key.of(String.class)), FieldInjectionPoint::keys);
    }

    @Test
    void rejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FieldInjectionPoint("x", null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl inject test -Dtest=InjectionPointTest`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Write `package-info.java` for the context package**

```java
/** Internal engine of the framework; public only so generated factories can extend {@link be.imgn.inject.context.Factory}. */
@NullMarked
package be.imgn.inject.context;

import org.jspecify.annotations.NullMarked;
```

- [ ] **Step 4: Write the sealed `InjectionPoint` and its three implementations**

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import java.util.List;

/** Metadata describing where dependencies are injected, for inspection and resolution. */
public sealed interface InjectionPoint
        permits ConstructorInjectionPoint, MethodInjectionPoint, FieldInjectionPoint {
    /** {@return the keys this injection point depends on, in declaration order} */
    List<Key<?>> keys();
}
```

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import java.util.List;
import java.util.Objects;

/**
 * The constructor injection point of a bean.
 *
 * @param keys the constructor parameter keys, in order
 */
public record ConstructorInjectionPoint(List<Key<?>> keys) implements InjectionPoint {
    /** @param keys the constructor parameter keys, in order */
    public ConstructorInjectionPoint {
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }

    /** {@return a no-argument constructor injection point} */
    public static ConstructorInjectionPoint none() {
        return new ConstructorInjectionPoint(List.of());
    }
}
```

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import java.util.List;
import java.util.Objects;

/**
 * A method injection point.
 *
 * @param name the method name (for inspection)
 * @param keys the method parameter keys, in order
 */
public record MethodInjectionPoint(String name, List<Key<?>> keys) implements InjectionPoint {
    /**
     * @param name the method name
     * @param keys the method parameter keys, in order
     */
    public MethodInjectionPoint {
        Objects.requireNonNull(name, "name");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }
}
```

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import java.util.List;
import java.util.Objects;

/**
 * A field injection point.
 *
 * @param name the field name (for inspection)
 * @param key the field's dependency key
 */
public record FieldInjectionPoint(String name, Key<?> key) implements InjectionPoint {
    /**
     * @param name the field name
     * @param key the field's dependency key
     */
    public FieldInjectionPoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(key, "key");
    }

    @Override
    public List<Key<?>> keys() {
        return List.of(key);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl inject test -Dtest=InjectionPointTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/context/InjectionPoint.java \
        inject/src/main/java/be/imgn/inject/context/ConstructorInjectionPoint.java \
        inject/src/main/java/be/imgn/inject/context/MethodInjectionPoint.java \
        inject/src/main/java/be/imgn/inject/context/FieldInjectionPoint.java \
        inject/src/main/java/be/imgn/inject/context/package-info.java \
        inject/src/test/java/be/imgn/inject/context/InjectionPointTest.java
git commit -m "feat: add injection point metadata types"
```

---

## Task 6: `Context` interface and `Factory` base

**Files:**
- Create: `be/imgn/inject/context/Context.java`, `be/imgn/inject/context/Factory.java`
- Test: `be/imgn/inject/context/FactoryTest.java`

**Interfaces:**
- Consumes: `Key`, `Provider`, injection points.
- Produces:
  - `Context`: `<T> Provider<T> getProvider(Key<T> key)`.
  - `Factory<T>`: `Factory(Class<T> type)`; `final Class<T> type()`; `final T build(Context)`; `abstract T createInstance(Context)`; overridable `injectMethods(T, Context)` / `injectFields(T, Context)`; overridable `constructorInjectionPoint()`, `methodInjectionPoints()`, `fieldInjectionPoints()`, `scope(): Optional<Class<? extends Annotation>>`, `exposedKeys(): List<Key<?>>`; `protected final <D> Provider<D> getProviderForConstructor(int, Context)`, `getInstanceForConstructor(int, Context)`, and the method/field analogues.

- [ ] **Step 1: Write the failing test with a hand-written factory**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactoryTest {
    /** A greeting bean whose message is injected via constructor. */
    record Greeting(String message) {}

    /** Minimal Context stub returning fixed providers. */
    static final class StubContext implements Context {
        private final Map<Key<?>, Provider<?>> bindings;

        StubContext(Map<Key<?>, Provider<?>> bindings) {
            this.bindings = bindings;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Provider<T> getProvider(Key<T> key) {
            return (Provider<T>) bindings.get(key);
        }
    }

    /** Hand-written factory standing in for generated output. */
    static final class GreetingFactory extends Factory<Greeting> {
        GreetingFactory() {
            super(Greeting.class);
        }

        @Override
        protected ConstructorInjectionPoint constructorInjectionPoint() {
            return new ConstructorInjectionPoint(List.of(Key.of(String.class)));
        }

        @Override
        protected Greeting createInstance(Context context) {
            return new Greeting(getInstanceForConstructor(0, context));
        }
    }

    @Test
    void buildResolvesConstructorDependency() {
        Context context = new StubContext(Map.of(Key.of(String.class), (Provider<String>) () -> "hi"));
        assertThat(new GreetingFactory())
                .returns(Greeting.class, Factory::type)
                .returns(List.of(Key.of(Greeting.class)), Factory::exposedKeys)
                .extracting(f -> f.build(context))
                .isEqualTo(new Greeting("hi"));
    }

    @Test
    void constructorRejectsNullType() {
        assertThatNullPointerException().isThrownBy(() -> new GreetingFactory() {
            { throw new NullPointerException("unused"); }
        });
        assertThatNullPointerException().isThrownBy(() -> new Factory<Object>(null) {
            @Override
            protected Object createInstance(Context context) {
                return new Object();
            }
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl inject test -Dtest=FactoryTest`
Expected: FAIL — `Context`/`Factory` do not exist.

- [ ] **Step 3: Write `Context`**

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;

/**
 * The internal resolution engine. Richer than {@code Injector} but accessible to
 * {@link Factory} so generated factories can resolve their dependencies.
 */
public interface Context {
    /**
     * {@return the provider bound to {@code key}}
     *
     * @param key the binding key
     * @param <T> the bound type
     * @throws be.imgn.inject.UnsatisfiedDependencyException if no binding exists
     */
    <T> Provider<T> getProvider(Key<T> key);
}
```

- [ ] **Step 4: Write `Factory`**

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Base class for per-bean factories. Generated subclasses live in the bean's own
 * package so they can access package-private members with zero reflection.
 *
 * @param <T> the built type
 */
public abstract class Factory<T> {
    private final Class<T> type;

    /** @param type the built type */
    protected Factory(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** {@return the built type} */
    public final Class<T> type() {
        return type;
    }

    /**
     * Builds and fully injects an instance.
     *
     * @param context the resolution context
     * @return the built instance
     */
    public final T build(Context context) {
        Objects.requireNonNull(context, "context");
        T instance = createInstance(context);
        injectMethods(instance, context);
        injectFields(instance, context);
        return instance;
    }

    /**
     * Creates the instance (constructor injection).
     *
     * @param context the resolution context
     * @return the new instance
     */
    protected abstract T createInstance(Context context);

    /**
     * Injects annotated methods. Empty by default.
     *
     * @param instance the instance to inject into
     * @param context the resolution context
     */
    protected void injectMethods(T instance, Context context) {}

    /**
     * Injects annotated fields. Empty by default.
     *
     * @param instance the instance to inject into
     * @param context the resolution context
     */
    protected void injectFields(T instance, Context context) {}

    /** {@return the constructor injection point; no-arg by default} */
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return ConstructorInjectionPoint.none();
    }

    /** {@return the method injection points; none by default} */
    protected List<MethodInjectionPoint> methodInjectionPoints() {
        return List.of();
    }

    /** {@return the field injection points; none by default} */
    protected List<FieldInjectionPoint> fieldInjectionPoints() {
        return List.of();
    }

    /** {@return the scoping annotation for this bean, or empty for prototype scope} */
    protected Optional<Class<? extends Annotation>> scope() {
        return Optional.empty();
    }

    /** {@return the keys this factory exposes; the bean type by default} */
    protected List<Key<?>> exposedKeys() {
        return List.of(Key.of(type));
    }

    /**
     * {@return the provider for the {@code paramIndex}-th constructor parameter}
     *
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForConstructor(int paramIndex, Context context) {
        return providerFor(constructorInjectionPoint().keys(), paramIndex, context, "constructor");
    }

    /**
     * {@return the resolved instance for the {@code paramIndex}-th constructor parameter}
     *
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> D getInstanceForConstructor(int paramIndex, Context context) {
        return this.<D>getProviderForConstructor(paramIndex, context).get();
    }

    /**
     * {@return the provider for the {@code paramIndex}-th parameter of the method at {@code methodIndex}}
     *
     * @param methodIndex the index into {@link #methodInjectionPoints()}
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForMethod(int methodIndex, int paramIndex, Context context) {
        return providerFor(methodInjectionPoints().get(methodIndex).keys(), paramIndex, context, "method");
    }

    /**
     * {@return the resolved instance for a method parameter}
     *
     * @param methodIndex the index into {@link #methodInjectionPoints()}
     * @param paramIndex the zero-based parameter index
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> D getInstanceForMethod(int methodIndex, int paramIndex, Context context) {
        return this.<D>getProviderForMethod(methodIndex, paramIndex, context).get();
    }

    /**
     * {@return the provider for the field at {@code fieldIndex}}
     *
     * @param fieldIndex the index into {@link #fieldInjectionPoints()}
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> Provider<D> getProviderForField(int fieldIndex, Context context) {
        return providerFor(fieldInjectionPoints().get(fieldIndex).keys(), 0, context, "field");
    }

    /**
     * {@return the resolved instance for a field}
     *
     * @param fieldIndex the index into {@link #fieldInjectionPoints()}
     * @param context the resolution context
     * @param <D> the dependency type
     */
    protected final <D> D getInstanceForField(int fieldIndex, Context context) {
        return this.<D>getProviderForField(fieldIndex, context).get();
    }

    @SuppressWarnings("unchecked")
    private <D> Provider<D> providerFor(List<Key<?>> keys, int index, Context context, String kind) {
        Objects.requireNonNull(context, "context");
        if (index < 0 || index >= keys.size()) {
            throw new IllegalArgumentException(
                    "No " + kind + " dependency at index " + index + " for " + type.getName());
        }
        return (Provider<D>) context.getProvider(keys.get(index));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl inject test -Dtest=FactoryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/context/Context.java \
        inject/src/main/java/be/imgn/inject/context/Factory.java \
        inject/src/test/java/be/imgn/inject/context/FactoryTest.java
git commit -m "feat: add Context interface and Factory base class"
```

---

## Task 7: Scope SPI, `FactoryProvider`, `SingletonProvider`, `SingletonScope`

**Files:**
- Create: `be/imgn/inject/context/Scope.java`, `FactoryProvider.java`, `SingletonProvider.java`, `SingletonScope.java`, `inject/src/main/resources/META-INF/services/be.imgn.inject.context.Scope`
- Modify: `inject/src/main/java/module-info.java` (re-enable the `provides`/`uses` lines from Task 1 Step 6)
- Test: `be/imgn/inject/context/SingletonProviderTest.java`, `FactoryProviderTest.java`

**Interfaces:**
- Consumes: `Provider`, `Factory`, `Context`, `Singleton` annotation.
- Produces:
  - `Scope`: `Class<? extends Annotation> annotationType()`; `<T> Provider<T> wrap(Provider<T> provider)`.
  - `FactoryProvider<T>(Context, Factory<T>)` implementing `Provider<T>` (`get()` = `factory.build(context)`).
  - `SingletonProvider<T>(Provider<T>)` — thread-safe once-only wrapper.
  - `SingletonScope` implements `Scope` (annotationType = `Singleton`, `wrap` returns a `SingletonProvider`).

- [ ] **Step 1: Write the failing tests**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.inject.Provider;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SingletonProviderTest {
    @Test
    void instantiatesExactlyOnceUnderContention() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Provider<Object> singleton = new SingletonProvider<>(() -> {
            calls.incrementAndGet();
            return new Object();
        });

        var results = IntStream.range(0, 64)
                .parallel()
                .mapToObj(i -> singleton.get())
                .distinct()
                .toList();

        assertThat(calls).hasValue(1);
        assertThat(results).hasSize(1);
    }
}
```

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactoryProviderTest {
    record Bean() {}

    static final class BeanFactory extends Factory<Bean> {
        BeanFactory() {
            super(Bean.class);
        }

        @Override
        protected Bean createInstance(Context context) {
            return new Bean();
        }
    }

    @Test
    void getBuildsViaFactory() {
        Context context = new Context() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Provider<T> getProvider(Key<T> key) {
                return (Provider<T>) (Provider<Bean>) new BeanFactory()::createInstance == null ? null : null;
            }
        };
        Provider<Bean> provider = new FactoryProvider<>(context, new BeanFactory());
        assertThat(provider.get()).isInstanceOf(Bean.class);
    }
}
```

Note: `FactoryProviderTest`'s stub context is never consulted (the bean has no dependencies); it exists only to satisfy the constructor. Simplify to `Context context = key -> null;` — a lambda is allowed since `Context` is a single-method interface. Replace the verbose stub with:

```java
    @Test
    void getBuildsViaFactory() {
        Context context = new Context() {
            @Override
            public <T> Provider<T> getProvider(Key<T> key) {
                throw new AssertionError("no dependencies expected");
            }
        };
        assertThat(new FactoryProvider<>(context, new BeanFactory()).get()).isInstanceOf(Bean.class);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl inject test -Dtest=SingletonProviderTest,FactoryProviderTest`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Write `Scope`**

```java
package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.lang.annotation.Annotation;

/**
 * Wraps a provider to give it a lifecycle. Implementations are discovered via
 * {@link java.util.ServiceLoader} and keyed by {@link #annotationType()}.
 */
public interface Scope {
    /** {@return the scoping annotation this scope handles} */
    Class<? extends Annotation> annotationType();

    /**
     * {@return {@code provider} wrapped with this scope's lifecycle}
     *
     * @param provider the unscoped provider
     * @param <T> the provided type
     */
    <T> Provider<T> wrap(Provider<T> provider);
}
```

- [ ] **Step 4: Write `FactoryProvider`**

```java
package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.util.Objects;

/**
 * A {@link Provider} that builds instances by invoking a {@link Factory} with a {@link Context}.
 *
 * @param <T> the provided type
 */
public final class FactoryProvider<T> implements Provider<T> {
    private final Context context;
    private final Factory<T> factory;

    /**
     * @param context the resolution context
     * @param factory the backing factory
     */
    public FactoryProvider(Context context, Factory<T> factory) {
        this.context = Objects.requireNonNull(context, "context");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public T get() {
        return factory.build(context);
    }
}
```

- [ ] **Step 5: Write `SingletonProvider`** (double-checked locking)

```java
package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a provider so its delegate is invoked exactly once; every {@link #get()}
 * returns the same instance. Thread-safe via double-checked locking.
 *
 * @param <T> the provided type
 */
public final class SingletonProvider<T> implements Provider<T> {
    private final Provider<T> delegate;
    private volatile @Nullable T instance;

    /** @param delegate the provider to memoize */
    public SingletonProvider(Provider<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public T get() {
        T local = instance;
        if (local == null) {
            synchronized (this) {
                local = instance;
                if (local == null) {
                    local = Objects.requireNonNull(delegate.get(), "delegate.get()");
                    instance = local;
                }
            }
        }
        return local;
    }
}
```

- [ ] **Step 6: Write `SingletonScope`**

```java
package be.imgn.inject.context;

import be.imgn.inject.Provider;
import be.imgn.inject.Singleton;
import java.lang.annotation.Annotation;

/** The built-in {@link Singleton} scope: memoizes the wrapped provider. */
public final class SingletonScope implements Scope {
    @Override
    public Class<? extends Annotation> annotationType() {
        return Singleton.class;
    }

    @Override
    public <T> Provider<T> wrap(Provider<T> provider) {
        return new SingletonProvider<>(provider);
    }
}
```

- [ ] **Step 7: Register the scope service**

Create `inject/src/main/resources/META-INF/services/be.imgn.inject.context.Scope` containing:

```
be.imgn.inject.context.SingletonScope
```

- [ ] **Step 8: Re-enable the module `provides`/`uses` lines**

Ensure `inject/src/main/java/module-info.java` matches Task 1 Step 6 exactly (uncomment the `uses`/`provides` lines if they were commented out).

- [ ] **Step 9: Run tests to verify they pass**

Run: `./mvnw -pl inject test -Dtest=SingletonProviderTest,FactoryProviderTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/context/Scope.java \
        inject/src/main/java/be/imgn/inject/context/FactoryProvider.java \
        inject/src/main/java/be/imgn/inject/context/SingletonProvider.java \
        inject/src/main/java/be/imgn/inject/context/SingletonScope.java \
        inject/src/main/resources/META-INF/services/be.imgn.inject.context.Scope \
        inject/src/main/java/module-info.java \
        inject/src/test/java/be/imgn/inject/context/SingletonProviderTest.java \
        inject/src/test/java/be/imgn/inject/context/FactoryProviderTest.java
git commit -m "feat: add Scope SPI, FactoryProvider, SingletonProvider, SingletonScope"
```

---

## Task 8: `DefaultContext` — registry, scope wrapping, lazy resolution, cycle guard

**Files:**
- Create: `be/imgn/inject/context/DefaultContext.java`, `CycleGuardProvider.java`
- Modify: `be/imgn/inject/context/Context.java` (add `static Context create()`)
- Test: `be/imgn/inject/context/DefaultContextTest.java`, plus test-scoped service files under `inject/src/test/resources/META-INF/services/`

**Interfaces:**
- Consumes: `Factory`, `Scope`, `FactoryProvider`, `Key`, exceptions.
- Produces: `Context.create()` returning a `DefaultContext`; `DefaultContext` loads `ServiceLoader<Factory>` and `ServiceLoader<Scope>`, builds an immutable `Key → Provider` map, wraps each `FactoryProvider` first in a `CycleGuardProvider` then in the factory's `Scope`, and enforces duplicate/missing/cycle rules. `CycleGuardProvider<T>(Key<?>, Provider<T>, DefaultContext)`.

- [ ] **Step 1: Write test fixtures (hand-written factories) and register them as test services**

Create `inject/src/test/java/be/imgn/inject/context/fixtures/` with two factories:

```java
package be.imgn.inject.context.fixtures;

import be.imgn.inject.Key;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;

/** Prototype leaf bean. */
public final class LeafFactory extends Factory<String> {
    public LeafFactory() {
        super(String.class);
    }

    @Override
    protected String createInstance(Context context) {
        return "leaf";
    }
}
```

```java
package be.imgn.inject.context.fixtures;

import be.imgn.inject.Key;
import be.imgn.inject.Singleton;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

/** Singleton-scoped bean depending on the leaf {@code String}. */
public final class RootFactory extends Factory<RootFactory.Root> {
    /** The produced bean. */
    public record Root(String leaf) {}

    public RootFactory() {
        super(Root.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(List.of(Key.of(String.class)));
    }

    @Override
    protected Optional<Class<? extends Annotation>> scope() {
        return Optional.of(Singleton.class);
    }

    @Override
    protected Root createInstance(Context context) {
        return new Root(getInstanceForConstructor(0, context));
    }
}
```

Create `inject/src/test/resources/META-INF/services/be.imgn.inject.context.Factory`:

```
be.imgn.inject.context.fixtures.LeafFactory
be.imgn.inject.context.fixtures.RootFactory
```

- [ ] **Step 2: Write the failing `DefaultContextTest`**

```java
package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.inject.Key;
import be.imgn.inject.UnsatisfiedDependencyException;
import be.imgn.inject.context.fixtures.RootFactory;
import org.junit.jupiter.api.Test;

class DefaultContextTest {
    @Test
    void resolvesPrototypeFresh() {
        Context context = Context.create();
        assertThat(context.getProvider(Key.of(String.class)).get()).isEqualTo("leaf");
    }

    @Test
    void singletonScopedBeanReturnsSameInstance() {
        Context context = Context.create();
        var provider = context.getProvider(Key.of(RootFactory.Root.class));
        assertThat(provider.get()).isSameAs(provider.get());
    }

    @Test
    void missingBindingThrowsUnsatisfied() {
        Context context = Context.create();
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> context.getProvider(Key.of(Long.class)).get())
                .withMessageContaining("Long");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -pl inject test -Dtest=DefaultContextTest`
Expected: FAIL — `Context.create()` / `DefaultContext` do not exist.

- [ ] **Step 4: Add `create()` to `Context`**

Add this method to the `Context` interface:

```java
    /** {@return a context loaded from all registered {@code Factory} and {@code Scope} services} */
    static Context create() {
        return new DefaultContext();
    }
```

- [ ] **Step 5: Write `CycleGuardProvider`**

```java
package be.imgn.inject.context;

import be.imgn.inject.Provider;
import java.util.Objects;

/** Detects re-entrant resolution of the same key and reports the cycle path. */
final class CycleGuardProvider<T> implements Provider<T> {
    private final be.imgn.inject.Key<?> key;
    private final Provider<T> delegate;
    private final DefaultContext context;

    CycleGuardProvider(be.imgn.inject.Key<?> key, Provider<T> delegate, DefaultContext context) {
        this.key = Objects.requireNonNull(key, "key");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public T get() {
        context.enter(key);
        try {
            return delegate.get();
        } finally {
            context.exit(key);
        }
    }
}
```

Note: the two `be.imgn.inject.Key<?>` references above violate the no-FQN Checkstyle rule — replace them with an `import be.imgn.inject.Key;` and use `Key<?>`. Corrected file:

```java
package be.imgn.inject.context;

import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import java.util.Objects;

/** Detects re-entrant resolution of the same key and reports the cycle path. */
final class CycleGuardProvider<T> implements Provider<T> {
    private final Key<?> key;
    private final Provider<T> delegate;
    private final DefaultContext context;

    CycleGuardProvider(Key<?> key, Provider<T> delegate, DefaultContext context) {
        this.key = Objects.requireNonNull(key, "key");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public T get() {
        context.enter(key);
        try {
            return delegate.get();
        } finally {
            context.exit(key);
        }
    }
}
```

- [ ] **Step 6: Write `DefaultContext`**

```java
package be.imgn.inject.context;

import be.imgn.inject.CircularDependencyException;
import be.imgn.inject.DuplicateBindingException;
import be.imgn.inject.Key;
import be.imgn.inject.Provider;
import be.imgn.inject.ScopeResolutionException;
import be.imgn.inject.UnsatisfiedDependencyException;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Default {@link Context}: loads all {@code Factory} and {@code Scope} services, wraps
 * each factory provider with a cycle guard and its declared scope, and resolves keys
 * lazily. The registry is immutable after construction; resolution is thread-safe.
 */
final class DefaultContext implements Context {
    private final Map<Key<?>, Provider<?>> registry;
    private final ThreadLocal<Deque<Key<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    DefaultContext() {
        Map<Class<? extends Annotation>, Scope> scopes = ServiceLoader.load(Scope.class).stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toMap(Scope::annotationType, s -> s));

        Map<Key<?>, Provider<?>> built = new HashMap<>();
        for (Factory<?> factory : ServiceLoader.load(Factory.class)) {
            Provider<?> provider = wrap(factory, scopes);
            for (Key<?> key : factory.exposedKeys()) {
                if (built.putIfAbsent(key, provider) != null) {
                    throw new DuplicateBindingException("Duplicate binding for " + key);
                }
            }
        }
        this.registry = Map.copyOf(built);
    }

    private <T> Provider<T> wrap(Factory<T> factory, Map<Class<? extends Annotation>, Scope> scopes) {
        Key<T> primary = Key.of(factory.type());
        Provider<T> guarded = new CycleGuardProvider<>(primary, new FactoryProvider<>(this, factory), this);
        Optional<Class<? extends Annotation>> scopeAnnotation = factory.scope();
        if (scopeAnnotation.isEmpty()) {
            return guarded;
        }
        Scope scope = scopes.get(scopeAnnotation.get());
        if (scope == null) {
            throw new ScopeResolutionException(
                    "No scope registered for @" + scopeAnnotation.get().getSimpleName());
        }
        return scope.wrap(guarded);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> getProvider(Key<T> key) {
        java.util.Objects.requireNonNull(key, "key");
        Provider<?> provider = registry.get(key);
        if (provider == null) {
            throw new UnsatisfiedDependencyException("No binding for " + key);
        }
        return (Provider<T>) provider;
    }

    void enter(Key<?> key) {
        Deque<Key<?>> current = stack.get();
        if (current.contains(key)) {
            String path = current.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(" -> "));
            throw new CircularDependencyException("Dependency cycle: " + path + " -> " + key);
        }
        current.push(key);
    }

    void exit(Key<?> key) {
        stack.get().pop();
    }
}
```

Note: replace the inline `java.util.Objects.requireNonNull` with an `import java.util.Objects;` and `Objects.requireNonNull(key, "key")` to satisfy Checkstyle.

- [ ] **Step 7: Add a cycle test using a self-referential fixture**

Add to `DefaultContextTest` (and a fixture pair `CycleAFactory`/`CycleBFactory` in the fixtures package registered in the test service file, each depending on the other's type):

```java
    @Test
    void cycleThrowsWithPath() {
        Context context = Context.create();
        assertThatExceptionOfType(be.imgn.inject.CircularDependencyException.class)
                .isThrownBy(() -> context.getProvider(
                        be.imgn.inject.Key.of(be.imgn.inject.context.fixtures.CycleAFactory.A.class)).get())
                .withMessageContaining("->");
    }
```

Fixtures (add both classes and append their names to the test `...Factory` service file):

```java
package be.imgn.inject.context.fixtures;

import be.imgn.inject.Key;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import java.util.List;

/** Half of a two-bean cycle: A depends on B. */
public final class CycleAFactory extends Factory<CycleAFactory.A> {
    /** Bean A. */
    public record A(CycleBFactory.B b) {}

    public CycleAFactory() {
        super(A.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(List.of(Key.of(CycleBFactory.B.class)));
    }

    @Override
    protected A createInstance(Context context) {
        return new A(getInstanceForConstructor(0, context));
    }
}
```

```java
package be.imgn.inject.context.fixtures;

import be.imgn.inject.Key;
import be.imgn.inject.context.ConstructorInjectionPoint;
import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;
import java.util.List;

/** Half of a two-bean cycle: B depends on A. */
public final class CycleBFactory extends Factory<CycleBFactory.B> {
    /** Bean B. */
    public record B(CycleAFactory.A a) {}

    public CycleBFactory() {
        super(B.class);
    }

    @Override
    protected ConstructorInjectionPoint constructorInjectionPoint() {
        return new ConstructorInjectionPoint(List.of(Key.of(CycleAFactory.A.class)));
    }

    @Override
    protected B createInstance(Context context) {
        return new B(getInstanceForConstructor(0, context));
    }
}
```

The `assertThatExceptionOfType(...)` lines above use FQNs for brevity; in the actual test file add imports and drop the qualifications to satisfy Checkstyle.

- [ ] **Step 8: Run tests to verify they pass**

Run: `./mvnw -pl inject test -Dtest=DefaultContextTest`
Expected: PASS (prototype fresh, singleton same-instance, missing throws, cycle throws with path).

- [ ] **Step 9: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/context/DefaultContext.java \
        inject/src/main/java/be/imgn/inject/context/CycleGuardProvider.java \
        inject/src/main/java/be/imgn/inject/context/Context.java \
        inject/src/test/java/be/imgn/inject/context/DefaultContextTest.java \
        inject/src/test/java/be/imgn/inject/context/fixtures \
        inject/src/test/resources/META-INF/services/be.imgn.inject.context.Factory
git commit -m "feat: add DefaultContext with scope wrapping and cycle guard"
```

---

## Task 9: `Injector` public entry point

**Files:**
- Create: `be/imgn/inject/Injector.java`, `be/imgn/inject/DefaultInjector.java`
- Test: `be/imgn/inject/InjectorTest.java`

**Interfaces:**
- Consumes: `Context` (`Context.create()`, `getProvider(Key)`), `Key`, `Provider`.
- Produces: `Injector` with `getInstance(Class)`, `getInstance(Key)`, `getProvider(Class)`, `getProvider(Key)`, and `static Injector create()`; package-private `DefaultInjector(Context)`.

- [ ] **Step 1: Write the failing test**

```java
package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class InjectorTest {
    @Test
    void convenienceMethodsDelegateToProviderByKey() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(String.class)).isEqualTo("leaf");
        assertThat(injector.getProvider(Key.of(String.class)).get()).isEqualTo("leaf");
        assertThat(injector.getInstance(Key.of(String.class))).isEqualTo("leaf");
    }

    @Test
    void nullArgumentsRejected() {
        Injector injector = Injector.create();
        assertThatNullPointerException().isThrownBy(() -> injector.getInstance((Class<?>) null));
        assertThatNullPointerException().isThrownBy(() -> injector.getProvider((Key<?>) null));
    }
}
```

(This test reuses the Task 8 test-scoped `LeafFactory` service registration for `String` → `"leaf"`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl inject test -Dtest=InjectorTest`
Expected: FAIL — `Injector` does not exist.

- [ ] **Step 3: Write `Injector`**

```java
package be.imgn.inject;

import be.imgn.inject.context.Context;

/** The user-facing entry point for obtaining instances and providers. */
public interface Injector {
    /**
     * {@return an instance of {@code type}}
     *
     * @param type the requested type
     * @param <T> the requested type
     */
    <T> T getInstance(Class<T> type);

    /**
     * {@return an instance for {@code key}}
     *
     * @param key the requested key
     * @param <T> the requested type
     */
    <T> T getInstance(Key<T> key);

    /**
     * {@return a provider of {@code type}}
     *
     * @param type the requested type
     * @param <T> the requested type
     */
    <T> Provider<T> getProvider(Class<T> type);

    /**
     * {@return the provider for {@code key}}
     *
     * @param key the requested key
     * @param <T> the requested type
     */
    <T> Provider<T> getProvider(Key<T> key);

    /** {@return a new injector backed by all registered factories and scopes} */
    static Injector create() {
        return new DefaultInjector(Context.create());
    }
}
```

- [ ] **Step 4: Write `DefaultInjector`**

```java
package be.imgn.inject;

import be.imgn.inject.context.Context;
import java.util.Objects;

/** Default {@link Injector} delegating everything to a {@link Context}. */
final class DefaultInjector implements Injector {
    private final Context context;

    DefaultInjector(Context context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public <T> T getInstance(Class<T> type) {
        return getProvider(type).get();
    }

    @Override
    public <T> T getInstance(Key<T> key) {
        return getProvider(key).get();
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        return getProvider(Key.of(Objects.requireNonNull(type, "type")));
    }

    @Override
    public <T> Provider<T> getProvider(Key<T> key) {
        return context.getProvider(Objects.requireNonNull(key, "key"));
    }
}
```

Note: `DefaultInjector` is in `be.imgn.inject` and needs to read `Context`, which lives in `be.imgn.inject.context` (exported). Fine — no reflection, ordinary API use.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl inject test -Dtest=InjectorTest`
Expected: PASS.

- [ ] **Step 6: Full build**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS — all tasks' tests pass, Spotless/Checkstyle/NullAway clean, Javadoc generates.

- [ ] **Step 7: Commit**

```bash
git add inject/src/main/java/be/imgn/inject/Injector.java \
        inject/src/main/java/be/imgn/inject/DefaultInjector.java \
        inject/src/test/java/be/imgn/inject/InjectorTest.java
git commit -m "feat: add Injector entry point delegating to Context"
```

---

## Self-Review Notes

- **Spec coverage:** §2 modules (parent + inject) → Task 1; §3 packages → Tasks 2–9 placement; §4 API (`Key`/`Provider`/`Injector`/annotations) → Tasks 2, 3, 9; §5 engine (`Context`/`FactoryProvider`/`Scope`/`Factory`/injection points) → Tasks 5–8; §9 validation + exceptions → Task 4 and per-task null checks; §10 tooling → Task 1. Processor (§6), module-path `provides` for factories (§7), plugin SPI, `@Provides`/`@Log` → **Plans 2 and 3** (out of scope here, by design).
- **Deferred to Plan 2:** the `provides be.imgn.inject.context.Factory with ...` directives for real beans (Task 1 registers only the `Scope` service; factory services in this plan are **test-scoped** fixtures).
- **Known ordering caveats flagged inline:** `Named` referenced by Task 2 tests (create it first or stub); `SingletonScope` referenced by the Task 1 module-info (comment out until Task 7).
