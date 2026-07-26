package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class LifecycleGenerationTest {

    @Test
    void generatesPostConstructAndPreDestroyOverrides() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Db", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;
                import be.imgn.inject.PreDestroy;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Db {
                    @Inject
                    public Db() {}

                    @PostConstruct
                    void connect() {}

                    @PreDestroy
                    void disconnect() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Db$$Factory")
                .contentsAsUtf8String();
        source.contains("protected void postConstruct(Db instance)");
        source.contains("instance.connect()");
        source.contains("protected void preDestroy(Db instance)");
        source.contains("instance.disconnect()");
        source.contains("protected boolean hasPreDestroy()");
        source.contains("return true");
    }

    @Test
    void prototypeWithPostConstructOnlyGeneratesNoPreDestroy() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Task", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Task {
                    @Inject
                    public Task() {}

                    @PostConstruct
                    void start() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Task$$Factory")
                .contentsAsUtf8String();
        source.contains("instance.start()");
        source.doesNotContain("hasPreDestroy");
    }

    @Test
    void assistedBeanCallsPostConstructInCreate() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Report", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Report {
                    @Inject
                    public Report(@Assisted String id) {}

                    @PostConstruct
                    void render() {}
                }
                """);
        JavaFileObject factory = JavaFileObjects.forSourceString("example.ReportFactory", """
                package example;

                import be.imgn.inject.AssistedFactory;

                public interface ReportFactory {
                    @AssistedFactory
                    Report create(String id);
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean, factory);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$ReportFactory$$Impl")
                .contentsAsUtf8String()
                .contains("instance.render()");
    }

    @Test
    void preDestroyOnUnscopedBeanIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PreDestroy;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PreDestroy
                    void stop() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("requires a scoped bean");
    }

    @Test
    void preDestroyOnAssistedBeanIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Assisted;
                import be.imgn.inject.Inject;
                import be.imgn.inject.PreDestroy;

                public class Bad {
                    @Inject
                    public Bad(@Assisted String id) {}

                    @PreDestroy
                    void stop() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("caller-owned");
    }

    @Test
    void twoPostConstructMethodsIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PostConstruct
                    void a() {}

                    @PostConstruct
                    void b() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("at most one");
    }

    @Test
    void callbackWithParametersIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PostConstruct
                    void start(String x) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("callback");
    }

    @Test
    void privateCallbackIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.PostConstruct;

                public class Bad {
                    @Inject
                    public Bad() {}

                    @PostConstruct
                    private void start() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("callback");
    }
}
