package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InterceptionValidationTest {

    private static final JavaFileObject TIMED = JavaFileObjects.forSourceString("example.Timed", """
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

    private static Compilation compile(String name, String source) {
        return javac().withProcessors(new InjectProcessor())
                .compile(TIMED, JavaFileObjects.forSourceString(name, source));
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
        assertThat(compilation).hadErrorContaining("enum");
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
        assertThat(compilation).hadErrorContaining("@Provides");
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
}
