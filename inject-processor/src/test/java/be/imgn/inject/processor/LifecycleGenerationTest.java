package be.imgn.inject.processor;

import static be.imgn.inject.processor.CompilationAssert.assertThat;
import static be.imgn.inject.processor.TestCompiler.javac;

import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class LifecycleGenerationTest {

    @Test
    void generatesPostConstructAndPreDestroyOverrides() {
        JavaFileObject bean = TestCompiler.source("example.Db", """
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
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Db$$Factory");
        source.contains("protected void postConstruct(Db instance)");
        source.contains("instance.connect()");
        source.contains("protected void preDestroy(Db instance)");
        source.contains("instance.disconnect()");
        source.contains("protected boolean hasPreDestroy()");
        source.contains("return true");
    }

    @Test
    void prototypeWithPostConstructOnlyGeneratesNoPreDestroy() {
        JavaFileObject bean = TestCompiler.source("example.Task", """
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
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Task$$Factory");
        source.contains("instance.start()");
        source.doesNotContain("hasPreDestroy");
    }

    @Test
    void assistedBeanCallsPostConstructInCreate() {
        JavaFileObject bean = TestCompiler.source("example.Report", """
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
        JavaFileObject factory = TestCompiler.source("example.ReportFactory", """
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
                .contains("instance.render()");
    }

    @Test
    void preDestroyOnUnscopedBeanIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
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
        JavaFileObject bean = TestCompiler.source("example.Bad", """
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
        JavaFileObject bean = TestCompiler.source("example.Bad", """
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
        JavaFileObject bean = TestCompiler.source("example.Bad", """
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
        JavaFileObject bean = TestCompiler.source("example.Bad", """
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
