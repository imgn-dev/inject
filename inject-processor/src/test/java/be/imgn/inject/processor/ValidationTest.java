package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ValidationTest {

    @Test
    void failsWhenDependencyHasNoFactoryAndNoNoArgConstructor() {
        JavaFileObject noDefault = JavaFileObjects.forSourceString("example.NoDefault", """
                package example;

                public class NoDefault {
                    public NoDefault(int value) {}
                }
                """);
        JavaFileObject client = JavaFileObjects.forSourceString("example.Client", """
                package example;

                import be.imgn.inject.Inject;

                public class Client {
                    @Inject
                    public Client(NoDefault dependency) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(noDefault, client);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("NoDefault");
    }

    @Test
    void failsOnDependencyCycle() {
        JavaFileObject a = JavaFileObjects.forSourceString("example.A", """
                package example;

                import be.imgn.inject.Inject;

                public class A {
                    @Inject
                    public A(B b) {}
                }
                """);
        JavaFileObject b = JavaFileObjects.forSourceString("example.B", """
                package example;

                import be.imgn.inject.Inject;

                public class B {
                    @Inject
                    public B(A a) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(a, b);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Dependency cycle");
    }

    @Test
    void providerDependencyBreaksCycle() {
        JavaFileObject a = JavaFileObjects.forSourceString("example.A", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class A {
                    @Inject
                    public A(Provider<B> b) {}
                }
                """);
        JavaFileObject b = JavaFileObjects.forSourceString("example.B", """
                package example;

                import be.imgn.inject.Inject;

                public class B {
                    @Inject
                    public B(A a) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(a, b);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$A$$Factory");
        assertThat(compilation).generatedSourceFile("example.$B$$Factory");
    }

    @Test
    void rejectsPrivateInjectField() {
        JavaFileObject bad = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;

                public class Bad {
                    @Inject
                    private String value;
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bad);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not be private");
    }
}
