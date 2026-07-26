package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InterceptorBeanTest {

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

    @Test
    void interceptorBeanFactoryReportsTheHandledAnnotation() {
        JavaFileObject interceptor = JavaFileObjects.forSourceString("example.TimingInterceptor", """
                package example;

                import be.imgn.inject.Intercepts;
                import be.imgn.inject.Singleton;
                import be.imgn.inject.context.MethodInterceptor;
                import be.imgn.inject.context.MethodInvocation;

                @Singleton
                @Intercepts(Timed.class)
                public final class TimingInterceptor implements MethodInterceptor {
                    @Override
                    public Object invoke(MethodInvocation invocation) throws Throwable {
                        return invocation.proceed();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, interceptor);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$TimingInterceptor$$Factory")
                .contentsAsUtf8String();
        source.contains("protected Optional<Class<? extends Annotation>> intercepts()");
        source.contains("return Optional.of(Timed.class)");
    }

    @Test
    void interceptorBeanDoesNotClaimTheMethodInterceptorKey() {
        JavaFileObject interceptor = JavaFileObjects.forSourceString("example.TimingInterceptor", """
                package example;

                import be.imgn.inject.Intercepts;
                import be.imgn.inject.context.MethodInterceptor;
                import be.imgn.inject.context.MethodInvocation;

                @Intercepts(Timed.class)
                public final class TimingInterceptor implements MethodInterceptor {
                    @Override
                    public Object invoke(MethodInvocation invocation) throws Throwable {
                        return invocation.proceed();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, interceptor);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$TimingInterceptor$$Factory")
                .contentsAsUtf8String()
                .doesNotContain("MethodInterceptor.class");
    }

    @Test
    void interceptsOnANonInterceptorIsRejected() {
        JavaFileObject notAnInterceptor = JavaFileObjects.forSourceString("example.Bogus", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Intercepts;

                @Intercepts(Timed.class)
                public final class Bogus {
                    @Inject
                    public Bogus() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, notAnInterceptor);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("does not implement MethodInterceptor");
    }

    @Test
    void anInterceptingAnnotationIsCapturedAsAStereotype() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                @Timed("service")
                public class Service {
                    @Inject
                    public Service() {}

                    public void run() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contentsAsUtf8String();
        source.contains("AnnotationValue.of(Timed.class");
        source.contains("InterceptingAnnotation.class");
    }
}
