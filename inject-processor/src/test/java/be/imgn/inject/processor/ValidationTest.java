package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ValidationTest {

    @Test
    void failsOnDependencyCycle() {
        JavaFileObject a = TestCompiler.source("example.A", """
                package example;

                import be.imgn.inject.Inject;

                public class A {
                    @Inject
                    public A(B b) {}
                }
                """);
        JavaFileObject b = TestCompiler.source("example.B", """
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
        JavaFileObject a = TestCompiler.source("example.A", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class A {
                    @Inject
                    public A(Provider<B> b) {}
                }
                """);
        JavaFileObject b = TestCompiler.source("example.B", """
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
        JavaFileObject bad = TestCompiler.source("example.Bad", """
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
