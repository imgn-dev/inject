package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class ExposeAsTest {

    @Test
    void defaultExposesBeanPlusAllSupertypes() {
        JavaFileObject base = JavaFileObjects.forSourceString(
                "example.BaseRepository", "package example;\n\npublic class BaseRepository {}\n");
        JavaFileObject audit = JavaFileObjects.forSourceString(
                "example.RepositoryAuditable", "package example;\n\npublic interface RepositoryAuditable {}\n");
        JavaFileObject repo = JavaFileObjects.forSourceString("example.UserRepository", """
                package example;

                import be.imgn.inject.Inject;

                public class UserRepository extends BaseRepository implements RepositoryAuditable {
                    @Inject
                    public UserRepository() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(base, audit, repo);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$UserRepository$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(UserRepository.class)");
        source.contains("Key.of(RepositoryAuditable.class)");
        source.contains("Key.of(BaseRepository.class)");
    }

    @Test
    void defaultExposureExcludesSerializable() {
        JavaFileObject base = JavaFileObjects.forSourceString("example.Y", "package example;\n\npublic class Y {}\n");
        JavaFileObject iface =
                JavaFileObjects.forSourceString("example.Z", "package example;\n\npublic interface Z {}\n");
        JavaFileObject x = JavaFileObjects.forSourceString("example.X", """
                package example;

                import be.imgn.inject.Inject;
                import java.io.Serializable;

                public class X extends Y implements Z, Serializable {
                    @Inject
                    public X() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(base, iface, x);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$X$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(X.class)");
        source.contains("Key.of(Y.class)");
        source.contains("Key.of(Z.class)");
        source.doesNotContain("Serializable");
    }

    @Test
    void exposeAsNarrowsToExactlyTheListedType() {
        JavaFileObject engine =
                JavaFileObjects.forSourceString("example.Engine", "package example;\n\npublic interface Engine {}\n");
        JavaFileObject v8 = JavaFileObjects.forSourceString("example.V8Engine", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;
                import java.io.Serializable;

                @Singleton
                @ExposeAs(Engine.class)
                public class V8Engine implements Engine, Serializable {
                    @Inject
                    public V8Engine() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(engine, v8);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$V8Engine$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(Engine.class)");
        source.doesNotContain("Key.of(V8Engine.class)");
        source.doesNotContain("Serializable");
    }

    @Test
    void exposeAsAcceptsMultipleTypes() {
        JavaFileObject printer =
                JavaFileObjects.forSourceString("example.Printer", "package example;\n\npublic interface Printer {}\n");
        JavaFileObject scanner =
                JavaFileObjects.forSourceString("example.Scanner", "package example;\n\npublic interface Scanner {}\n");
        JavaFileObject copier =
                JavaFileObjects.forSourceString("example.Copier", "package example;\n\npublic interface Copier {}\n");
        JavaFileObject device = JavaFileObjects.forSourceString("example.Device", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs({Printer.class, Scanner.class})
                public class Device implements Printer, Scanner, Copier {
                    @Inject
                    public Device() {}
                }
                """);

        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(printer, scanner, copier, device);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Device$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(Printer.class)");
        source.contains("Key.of(Scanner.class)");
        source.doesNotContain("Key.of(Copier.class)");
        source.doesNotContain("Key.of(Device.class)");
    }

    @Test
    void exposeAsAcceptsIndirectSupertype() {
        JavaFileObject vehicle =
                JavaFileObjects.forSourceString("example.Vehicle", "package example;\n\npublic interface Vehicle {}\n");
        JavaFileObject car = JavaFileObjects.forSourceString(
                "example.Car", "package example;\n\npublic interface Car extends Vehicle {}\n");
        JavaFileObject sportsCar = JavaFileObjects.forSourceString("example.SportsCar", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs(Vehicle.class)
                public class SportsCar implements Car {
                    @Inject
                    public SportsCar() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(vehicle, car, sportsCar);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$SportsCar$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(Vehicle.class)");
        source.doesNotContain("Key.of(Car.class)");
        source.doesNotContain("Key.of(SportsCar.class)");
    }

    @Test
    void emptyExposeAsFails() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Empty", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs({})
                public class Empty {
                    @Inject
                    public Empty() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must specify at least one type");
    }

    @Test
    void unrelatedExposeAsFails() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Widget", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;

                @ExposeAs(String.class)
                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("unrelated type");
    }

    @Test
    void exposeAsOnMethodWithoutProvidesFails() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.ExposeAs;

                public class Bad {
                    @ExposeAs(Object.class)
                    public void notProvides() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@ExposeAs on a method is only allowed together with @Provides");
    }

    @Test
    void qualifierAppliesToEveryExposedKey() {
        JavaFileObject audit = JavaFileObjects.forSourceString(
                "example.RepositoryAuditable", "package example;\n\npublic interface RepositoryAuditable {}\n");
        JavaFileObject repo = JavaFileObjects.forSourceString("example.UserRepository", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Named;

                @Named("primary-db")
                public class UserRepository implements RepositoryAuditable {
                    @Inject
                    public UserRepository() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(audit, repo);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$UserRepository$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(UserRepository.class,");
        source.contains("Key.of(RepositoryAuditable.class,");
        source.contains("Map.of(\"value\", \"primary-db\")");
    }
}
