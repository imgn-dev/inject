package be.imgn.inject.processor;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Names;

/**
 * Pushes one intercepted bean through every container mechanic at once — {@code @ExposeAs}, a qualifier,
 * {@code @Singleton}, {@code @PostConstruct}/{@code @PreDestroy} — asserting interception holds in each case, then
 * checks the two ways a child injector can resolve an intercepted bean: a {@code @Singleton} anchors to the parent and
 * comes back as the same intercepted instance, while an unscoped bean is rebuilt at the child (via
 * {@code ChildContext}'s {@code factory.build(this)}) and is still intercepted. {@link InterceptionEndToEndTest} covers
 * the bare constructor-injection case; this locks in that stacking the rest of the container's machinery on top does
 * not silently drop the chain.
 */
class InterceptionLifecycleIntegrationTest {

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

    private static final JavaFileObject LOG = JavaFileObjects.forSourceString("example.CallLog", """
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

    private static final JavaFileObject INTERCEPTOR = JavaFileObjects.forSourceString("example.TimingInterceptor", """
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
                    log.add("enter:" + invocation.methodName());
                    Object result = invocation.proceed();
                    log.add("exit:" + invocation.methodName());
                    return result;
                }
            }
            """);

    private static final JavaFileObject REPORTER = JavaFileObjects.forSourceString("example.Reporter", """
            package example;

            public interface Reporter {
                String report();
            }
            """);

    private static final JavaFileObject ORDERS_REPORT = JavaFileObjects.forSourceString("example.OrdersReport", """
            package example;

            import be.imgn.inject.ExposeAs;
            import be.imgn.inject.Inject;
            import be.imgn.inject.Named;
            import be.imgn.inject.PostConstruct;
            import be.imgn.inject.PreDestroy;
            import be.imgn.inject.Singleton;

            @Singleton
            @Named("orders")
            @ExposeAs(Reporter.class)
            public class OrdersReport implements Reporter {
                private final CallLog log;

                @Inject
                public OrdersReport(CallLog log) {
                    this.log = log;
                }

                @Timed("report")
                @Override
                public String report() {
                    return "report";
                }

                @PostConstruct
                void started() {
                    log.add("postConstruct");
                }

                @PreDestroy
                void stopped() {
                    log.add("preDestroy");
                }
            }
            """);

    private static final JavaFileObject WORKER = JavaFileObjects.forSourceString("example.Worker", """
            package example;

            import be.imgn.inject.Inject;

            public class Worker {
                private final CallLog log;

                @Inject
                public Worker(CallLog log) {
                    this.log = log;
                }

                @Timed("work")
                public String work() {
                    log.add("worked");
                    return "worked";
                }
            }
            """);

    @Test
    void interceptionSurvivesExposureQualifierScopeLifecycleAndAChildInjector(@TempDir Path outputDir)
            throws Exception {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(TIMED, LOG, INTERCEPTOR, REPORTER, ORDERS_REPORT, WORKER);
        CompilationSubject.assertThat(compilation).succeeded();
        writeClassOutput(compilation, outputDir);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {outputDir.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (Injector injector = Injector.create()) {
                Class<?> reporterType = loader.loadClass("example.Reporter");
                Class<?> logType = loader.loadClass("example.CallLog");
                Key<?> key = Key.of(reporterType, Names.named("orders"));
                Method entries = logType.getMethod("entries");
                Method report = reporterType.getMethod("report");

                // @ExposeAs + qualifier: reachable only under Reporter.class qualified "orders", and @Singleton +
                // @PostConstruct: constructing it on first resolution already ran the callback.
                Object reporter = injector.getInstance(key);
                Object log = injector.getInstance(logType);
                assertThat(entries.invoke(log))
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("the singleton is built and @PostConstruct runs before first use")
                        .containsExactly("postConstruct");

                // Child injector: a singleton anchors to the parent, so the same intercepted instance comes back.
                Injector child = injector.childBuilder().build();
                Object throughChild = child.getInstance(key);
                assertThat(throughChild)
                        .as("a singleton bean resolves to the same intercepted instance through a child injector")
                        .isSameAs(reporter);

                Object result = report.invoke(throughChild);

                assertThat(result).isEqualTo("report");
                assertThat(entries.invoke(log))
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("a call on the instance resolved through the child injector is still intercepted")
                        .containsExactly("postConstruct", "enter:report", "exit:report");

                // Unscoped bean through a child: ChildContext rebuilds it locally (factory.build(this)) instead of
                // delegating to the parent, so its own dependencies resolve through the child, not the parent. A
                // child-only CallLog override proves that: only a Worker built through the child can see it.
                Class<?> workerType = loader.loadClass("example.Worker");
                Method work = workerType.getMethod("work");
                @SuppressWarnings("unchecked")
                Class<Object> logClass = (Class<Object>) logType;
                Object childLog = logType.getDeclaredConstructor().newInstance();
                Injector workerChild =
                        injector.childBuilder().bind(logClass, childLog).build();

                Object rebuiltByChild = workerChild.getInstance(workerType);
                Object workResult = work.invoke(rebuiltByChild);

                assertThat(workResult).isEqualTo("worked");
                assertThat(entries.invoke(childLog))
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("an unscoped bean resolved through a child is rebuilt there, seeing the child's own"
                                + " CallLog binding rather than the parent's")
                        .containsExactly("worked");
                assertThat(entries.invoke(log))
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("interception still holds for the unscoped bean the child rebuilt: the shared"
                                + " interceptor still logs to the parent's CallLog")
                        .containsExactly("postConstruct", "enter:report", "exit:report", "enter:work", "exit:work");

                workerChild.close();
                child.close();
                injector.close();
                assertThat(entries.invoke(log))
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .as("closing the owning injector still runs @PreDestroy")
                        .containsExactly(
                                "postConstruct",
                                "enter:report",
                                "exit:report",
                                "enter:work",
                                "exit:work",
                                "preDestroy");
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
