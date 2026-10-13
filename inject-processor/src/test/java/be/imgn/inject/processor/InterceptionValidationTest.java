package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class InterceptionValidationTest {

    private static final JavaFileObject TIMED = TestCompiler.source("example.Timed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Timed {
                String value() default "";
            }
            """);

    private static final JavaFileObject FIELD_TIMED = TestCompiler.source("example.FieldTimed", """
            package example;

            import be.imgn.inject.InterceptingAnnotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @InterceptingAnnotation
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.FIELD)
            public @interface FieldTimed {
            }
            """);

    private static Compilation compile(String name, String source) {
        return javac().withProcessors(new InjectProcessor()).compile(TIMED, TestCompiler.source(name, source));
    }

    @Test
    void finalClassCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public final class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Remove final");
    }

    @Test
    void recordCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public record Bean(String name) {
                    @Inject
                    public Bean {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("record");
    }

    @Test
    void enumCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                @Timed
                public enum Bean {
                    ONE;

                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be intercepted");
    }

    @Test
    void nonInterceptedEnumWithInjectMemberFailsAtSource() {
        Compilation compilation = compile("example.Bean2", """
                package example;

                import be.imgn.inject.Inject;

                public enum Bean2 {
                    ONE;

                    @Inject
                    String value;
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("example.Bean2 has @Inject members but no @Inject constructor and no accessible"
                        + " no-argument constructor.");
        Assertions.assertThat(compilation.errors())
                .noneMatch(diagnostic -> diagnostic.getSource() != null
                        && diagnostic.getSource().getName().contains("$$Factory"));
    }

    @Test
    void rejectedShapeInjectedElsewhereReportsExactlyOneError() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(TIMED, TestCompiler.source("example.Foo", """
                                package example;

                                public final class Foo {
                                    public Foo() {}

                                    @Timed
                                    public void run() {}
                                }
                                """), TestCompiler.source("example.Bar", """
                                package example;

                                import be.imgn.inject.Inject;

                                public class Bar {
                                    @Inject
                                    public Bar(Foo foo) {}
                                }
                                """));

        assertThat(compilation).failed();
        Assertions.assertThat(compilation.errors())
                .singleElement()
                .satisfies(diagnostic ->
                        Assertions.assertThat(diagnostic.getMessage(null)).contains("Remove final"));
    }

    @Test
    void sealedClassCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public sealed class Bean permits Bean.Only {
                    @Inject
                    public Bean() {}

                    @Timed
                    public void run() {}

                    public static final class Only extends Bean {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("sealed");
    }

    @Test
    void genericClassCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean<T> {
                    @Inject
                    public Bean() {}

                    @Timed
                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("generic class");
    }

    @Test
    void finalMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public final void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("final");
    }

    @Test
    void genericThrowsMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public <E extends Exception> void run() throws E {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("a type variable in its throws clause cannot be caught");
    }

    @Test
    void classLevelAnnotationSkipsGenericThrowsMethodButKeepsOrdinaryOne() throws IOException {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Bean {
                    @Inject
                    public Bean() {}

                    public <E extends Exception> void risky() throws E {}

                    public void run() {}
                }
                """);

        assertThat(compilation).succeeded();
        String source = compilation
                .generatedSourceFile("example.$Bean$$Intercepted")
                .orElseThrow()
                .getCharContent(true)
                .toString();
        Assertions.assertThat(source).contains("void run()").doesNotContain("risky");
    }

    @Test
    void staticMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    public static void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("static");
    }

    @Test
    void privateMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    private void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("private");
    }

    @Test
    void frameworkCallbackMethodsCannotBeIntercepted() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bean {
                    @Inject
                    public Bean() {}

                    @Timed
                    @PostConstruct
                    public void start() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("framework callbacks");
    }

    @Test
    void providesMethodCannotBeIntercepted() {
        Compilation compilation = compile("example.Config", """
                package example;

                import be.imgn.inject.Provides;

                public class Config {
                    @Timed
                    @Provides
                    public static String value() {
                        return "v";
                    }
                }
                """);

        assertThat(compilation).failed();
        Assertions.assertThat(compilation.errors())
                .singleElement()
                .satisfies(diagnostic ->
                        Assertions.assertThat(diagnostic.getMessage(null)).contains("@Provides"));
    }

    @Test
    void assistedRecipeCannotBeIntercepted() {
        Compilation compilation = compile("example.Ticket", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;

                @Timed
                public class Ticket {
                    @Inject
                    public Ticket(@Assisted String label) {}

                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("assisted");
    }

    @Test
    void anInterceptorCannotItselfBeIntercepted() {
        Compilation compilation = compile("example.Recursive", """
                package example;

                import be.imgn.inject.Intercepts;
                import be.imgn.inject.context.MethodInterceptor;
                import be.imgn.inject.context.MethodInvocation;

                @Timed
                @Intercepts(Timed.class)
                public class Recursive implements MethodInterceptor {
                    @Override
                    public Object invoke(MethodInvocation invocation) throws Throwable {
                        return invocation.proceed();
                    }
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot itself be intercepted");
    }

    @Test
    void interceptorNotImplementingMethodInterceptorReportsExactlyOneError() {
        Compilation compilation = compile("example.NotAnInterceptor", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Intercepts;

                @Timed
                @Intercepts(Timed.class)
                public class NotAnInterceptor {
                    @Inject
                    public NotAnInterceptor() {}

                    public void run() {}
                }
                """);

        assertThat(compilation).failed();
        Assertions.assertThat(compilation.errors())
                .singleElement()
                .satisfies(diagnostic -> Assertions.assertThat(diagnostic.getMessage(null))
                        .contains("does not implement MethodInterceptor"));
    }

    @Test
    void classLevelAnnotationWithNoEligibleMethodIsRejected() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Bean {
                    @Inject
                    public Bean() {}

                    public final String name() {
                        return "bean";
                    }
                }
                """);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("no interceptable method");
    }

    @Test
    void classLevelAnnotationSkipsIneligibleMethodsWhenOneIsEligible() {
        Compilation compilation = compile("example.Bean", """
                package example;

                import be.imgn.inject.Inject;

                @Timed
                public class Bean {
                    @Inject
                    public Bean() {}

                    public final String name() {
                        return "bean";
                    }

                    public void run() {}
                }
                """);

        assertThat(compilation).succeeded();
    }

    @Test
    void interceptingAnnotationOnFieldOfUnrelatedClassGeneratesNoFactory() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(FIELD_TIMED, TestCompiler.source("example.Plain", """
                                package example;

                                public class Plain {
                                    @FieldTimed
                                    String value;
                                }
                                """));

        assertThat(compilation).succeeded();
        Assertions.assertThat(compilation.generatedSourceFiles())
                .noneMatch(file -> file.getName().contains("$Plain$$Factory"));
    }
}
