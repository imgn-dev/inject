package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.CompilationAssert;
import be.imgn.gaia.processing.testing.TestCompiler;
import be.imgn.inject.Injector;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterceptionEndToEndTest {

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

    private static final JavaFileObject LOG = TestCompiler.source("example.CallLog", """
            package example;

            import be.imgn.inject.Inject;
            import be.imgn.inject.Singleton;
            import java.util.ArrayList;
            import java.util.List;

            @Singleton
            public class CallLog {
                private final List<String> entries = new ArrayList<>();

                @Inject
                public CallLog() {}

                public void add(String entry) {
                    entries.add(entry);
                }

                public List<String> entries() {
                    return entries;
                }
            }
            """);

    private static final JavaFileObject INTERCEPTOR = TestCompiler.source("example.TimingInterceptor", """
            package example;

            import be.imgn.inject.Inject;
            import be.imgn.inject.Intercepts;
            import be.imgn.inject.Singleton;
            import be.imgn.inject.context.MethodInterceptor;
            import be.imgn.inject.context.MethodInvocation;

            @Singleton
            @Intercepts(Timed.class)
            public class TimingInterceptor implements MethodInterceptor {
                private final CallLog log;

                @Inject
                public TimingInterceptor(CallLog log) {
                    this.log = log;
                }

                @Override
                public Object invoke(MethodInvocation invocation) throws Throwable {
                    log.add("enter:" + invocation.methodName() + ":"
                            + invocation.annotation().stringValue("value").orElse(""));
                    Object result = invocation.proceed();
                    log.add("exit:" + invocation.methodName());
                    return result;
                }
            }
            """);

    @Test
    void interceptsThroughARealInjector(@TempDir Path outputDir) throws Exception {
        JavaFileObject bean = TestCompiler.source("example.Orders", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Orders {
                    @Inject
                    public Orders() {}

                    @Timed("place")
                    public String place(String cart) {
                        return "placed:" + cart + ":" + self();
                    }

                    @Timed
                    public String self() {
                        return "self";
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, LOG, INTERCEPTOR, bean);
        CompilationAssert.assertThat(compilation).succeeded();
        writeClassOutput(compilation, outputDir);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {outputDir.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (Injector injector = Injector.create()) {
                Class<?> ordersType = loader.loadClass("example.Orders");
                Object orders = injector.getInstance(ordersType);
                Method place = ordersType.getMethod("place", String.class);

                Object result = place.invoke(orders, "cart-1");

                assertThat(result).isEqualTo("placed:cart-1:self");
                Class<?> logType = loader.loadClass("example.CallLog");
                Object log = injector.getInstance(logType);
                Object entries = logType.getMethod("entries").invoke(log);
                assertThat(entries)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly("enter:place:place", "enter:self:", "exit:self", "exit:place");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    void constructorCallToAnInterceptedMethodRunsUnintercepted(@TempDir Path outputDir) throws Exception {
        JavaFileObject bean = TestCompiler.source("example.Bootstrapping", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Bootstrapping {
                    private final String duringConstruction;

                    @Inject
                    public Bootstrapping() {
                        this.duringConstruction = greet();
                    }

                    @Timed
                    public String greet() {
                        return "hello";
                    }

                    public String duringConstruction() {
                        return duringConstruction;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(TIMED, LOG, INTERCEPTOR, bean);
        CompilationAssert.assertThat(compilation).succeeded();
        writeClassOutput(compilation, outputDir);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {outputDir.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (Injector injector = Injector.create()) {
                Class<?> beanType = loader.loadClass("example.Bootstrapping");
                Object bootstrapping = injector.getInstance(beanType);

                Object duringConstruction =
                        beanType.getMethod("duringConstruction").invoke(bootstrapping);
                assertThat(duringConstruction).isEqualTo("hello");

                Class<?> logType = loader.loadClass("example.CallLog");
                Object log = injector.getInstance(logType);
                Object entriesAfterConstruction = logType.getMethod("entries").invoke(log);
                assertThat(entriesAfterConstruction)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("the constructor's own call to greet() must not be intercepted")
                        .isEmpty();

                Object laterResult = beanType.getMethod("greet").invoke(bootstrapping);
                assertThat(laterResult).isEqualTo("hello");

                Object entriesAfterLaterCall = logType.getMethod("entries").invoke(log);
                assertThat(entriesAfterLaterCall)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("a call once the instance is fully constructed must be intercepted")
                        .containsExactly("enter:greet:", "exit:greet");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private static void writeClassOutput(Compilation compilation, Path outputDir) throws Exception {
        String marker = "CLASS_OUTPUT/";
        for (JavaFileObject file : compilation.generatedFiles()) {
            String name = file.getName();
            int index = name.indexOf(marker);
            if (index < 0) {
                continue;
            }
            Path target = outputDir.resolve(name.substring(index + marker.length()));
            Files.createDirectories(target.getParent());
            try (InputStream in = file.openInputStream()) {
                Files.write(target, in.readAllBytes());
            }
        }
    }
}
